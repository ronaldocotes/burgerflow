package com.menuflow.service

import com.menuflow.dto.CartSessionResponse
import com.menuflow.event.OrderPaidEvent
import com.menuflow.model.CartSession
import com.menuflow.model.CartSessionStatus
import com.menuflow.model.Order
import com.menuflow.model.PaymentStatus
import com.menuflow.repository.tenant.CartSessionRepository
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * Recuperacao de carrinho abandonado (Fase 3.5). Todo pedido que nasce PENDENTE de
 * pagamento e com telefone do cliente vira uma [CartSession] ACTIVE; um job periodico
 * (CartRecoveryJob -> [processAbandonedCarts]) envia, apos um atraso configuravel, uma
 * mensagem de recuperacao por WhatsApp. Pagar o pedido -> RECOVERED; estourar o prazo
 * sem pagar -> EXPIRED. Tudo no banco do TENANT (db-per-tenant).
 *
 * RISCO WAHA: o disparo e PROATIVO (15-30% de risco de ban/ano na API nao-oficial).
 * A janela "reativa segura" e garantida pelo proprio desenho: a comanda nasce junto
 * com um pedido recem-criado e so e mensageada se ainda estiver DENTRO do prazo de
 * expiracao (default 2h, muito abaixo de 24h) — passou o prazo, vira EXPIRED e nunca
 * recebe mensagem. Logo so contatamos quem fez um pedido ha pouco (janela reativa).
 */
@Service
class CartRecoveryService(
    private val cartSessionRepository: CartSessionRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val customerRepository: CustomerRepository,
    private val whatsAppService: WhatsAppService,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
    @Value("\${menuflow.public-menu-url:https://menuflow.duckdns.org/cardapio}")
    private val publicMenuUrl: String,
    @Value("\${menuflow.cart-recovery.send-delay-min-seconds:15}")
    private val sendDelayMinSeconds: Int,
    @Value("\${menuflow.cart-recovery.send-delay-max-seconds:45}")
    private val sendDelayMaxSeconds: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    /**
     * Cria a comanda de recuperacao ao criar um pedido. Chamado por OrderService.create.
     *
     * So cria se o pedido nasceu PENDENTE de pagamento E tem telefone do cliente. NUNCA
     * pode derrubar a criacao do pedido:
     *  - se ha transacao ativa (fluxo real do create): registra a insercao para DEPOIS
     *    do commit (AFTER_COMMIT). Isso e obrigatorio porque a FK cart_sessions.order_id
     *    referencia orders(id) — o pedido precisa estar comitado/visivel — e porque uma
     *    falha aqui, pos-commit, nao reverte o pedido;
     *  - se NAO ha transacao ativa (ex.: teste service-level com o pedido ja comitado):
     *    insere imediatamente em transacao propria.
     * Em ambos os casos a insercao e protegida por try/catch (fail-safe).
     */
    fun onOrderCreated(order: Order) {
        if (order.customerPhone.isNullOrBlank()) return
        if (order.paymentStatus != PaymentStatus.PENDING) return
        val orderId = order.id ?: return
        val phone = order.customerPhone
        val total = order.totalCents
        val slug = TenantContext.get() ?: return

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        createSessionSafely(slug, orderId, phone, total)
                    }
                },
            )
        } else {
            createSessionSafely(slug, orderId, phone, total)
        }
    }

    /** Insere a CartSession em transacao propria, idempotente e fail-safe. */
    private fun createSessionSafely(slug: String, orderId: UUID, phone: String?, totalCents: Long) {
        val previous = TenantContext.get()
        TenantContext.set(slug)
        try {
            txTemplate.execute {
                // Idempotencia: o indice UNIQUE uq_cart_session_order tambem garante,
                // mas a checagem evita a excecao de violacao no caminho normal.
                if (!cartSessionRepository.existsByOrderId(orderId)) {
                    cartSessionRepository.save(
                        CartSession(
                            orderId = orderId,
                            customerPhone = phone,
                            totalCents = totalCents,
                            status = CartSessionStatus.ACTIVE,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            // Fail-safe: a recuperacao de carrinho nunca pode derrubar o pedido.
            log.error("Falha ao criar comanda de recuperacao do pedido {}: {}", orderId, e.message)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /**
     * Reage ao pagamento do pedido (OrderPaidEvent) APOS o commit: marca a comanda
     * correspondente como RECOVERED. Delega para [applyOrderPaid] (testavel direto).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderPaid(event: OrderPaidEvent) {
        applyOrderPaid(event)
    }

    /**
     * Marca como RECOVERED a comanda do pedido pago. Vincula o tenant a partir do slug
     * do evento (o thread pode ter perdido o contexto pos-commit). Fail-open total.
     * So transiciona se a comanda estiver ACTIVE ou SENT (RECOVERED/EXPIRED sao finais).
     */
    fun applyOrderPaid(event: OrderPaidEvent) {
        val previous = TenantContext.get()
        TenantContext.set(event.tenantSlug)
        try {
            txTemplate.execute {
                val cart = cartSessionRepository.findByOrderId(event.orderId) ?: return@execute
                if (cart.status == CartSessionStatus.ACTIVE || cart.status == CartSessionStatus.SENT) {
                    cart.status = CartSessionStatus.RECOVERED
                    cart.recoveredAt = Instant.now()
                    cartSessionRepository.save(cart)
                }
            }
        } catch (e: Exception) {
            log.error("Falha ao marcar carrinho recuperado do pedido {}: {}", event.orderId, e.message)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /**
     * Tick do job para UM tenant: envia as mensagens de recuperacao pendentes.
     *  1. respeita o liga/desliga (cart_recovery_enabled);
     *  2. seleciona as comandas ACTIVE cujo pedido passou do atraso configurado;
     *  3. por comanda: se ja passou do prazo de expiracao -> EXPIRED (nao envia);
     *     senao, com telefone, envia pelo WAHA e marca SENT (falha mantem ACTIVE para
     *     o proximo tick); aplica delay aleatorio (15-45s) entre envios.
     * Retorna o numero de mensagens efetivamente enviadas.
     */
    fun processAbandonedCarts(tenantSlug: String): Int {
        val previous = TenantContext.get()
        TenantContext.set(tenantSlug)
        try {
            val config = txTemplate.execute { tenantConfigRepository.findFirstByOrderByCreatedAtAsc() } ?: return 0
            if (!config.cartRecoveryEnabled) return 0

            val now = Instant.now()
            val delayCutoff = now.minus(config.cartRecoveryDelayMinutes.toLong(), ChronoUnit.MINUTES)
            val candidates = txTemplate.execute {
                cartSessionRepository.findByStatusAndCreatedAtLessThan(CartSessionStatus.ACTIVE, delayCutoff)
            } ?: emptyList()

            val session = config.wahaPrimaryPhone
            var sent = 0
            for ((index, cart) in candidates.withIndex()) {
                val expiryCutoff = cart.createdAt.plus(config.cartRecoveryExpiryHours.toLong(), ChronoUnit.HOURS)
                if (expiryCutoff.isBefore(now)) {
                    txTemplate.execute { markExpired(cart.id!!, now) }
                    continue
                }
                val phone = cart.customerPhone
                if (phone.isNullOrBlank()) continue

                val name = txTemplate.execute { customerRepository.findByPhoneNumber(phone)?.name } ?: "cliente"
                val message = render(config.cartRecoveryMessage, name, cart.totalCents)

                // Envio FORA de transacao (HTTP externo nao prende conexao do banco).
                val ok = whatsAppService.sendCampaign(phone, message, session)
                if (ok) {
                    txTemplate.execute { markSent(cart.id!!, now) }
                    sent++
                    if (index < candidates.lastIndex) sleepBetween()
                }
                // Falha: mantem ACTIVE para tentar de novo no proximo tick.
            }
            return sent
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /** Listagem paginada para o painel do admin (status opcional). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(status: CartSessionStatus?, pageable: Pageable): Page<CartSessionResponse> {
        val page = if (status != null) {
            cartSessionRepository.findByStatus(status, pageable)
        } else {
            cartSessionRepository.findAll(pageable)
        }
        return page.map { CartSessionResponse.from(it) }
    }

    private fun markExpired(id: UUID, now: Instant) {
        cartSessionRepository.findById(id).orElse(null)?.let {
            if (it.status == CartSessionStatus.ACTIVE) {
                it.status = CartSessionStatus.EXPIRED
                it.expiredAt = now
                cartSessionRepository.save(it)
            }
        }
    }

    private fun markSent(id: UUID, now: Instant) {
        cartSessionRepository.findById(id).orElse(null)?.let {
            if (it.status == CartSessionStatus.ACTIVE) {
                it.status = CartSessionStatus.SENT
                it.recoveryMessageSentAt = now
                cartSessionRepository.save(it)
            }
        }
    }

    /** Interpola {nome}, {total} (R$) e {link} na mensagem configurada. */
    private fun render(template: String?, name: String, totalCents: Long): String {
        val base = template ?: DEFAULT_MESSAGE
        val total = String.format(Locale("pt", "BR"), "R$ %.2f", totalCents / 100.0)
        return base
            .replace("{nome}", name)
            .replace("{total}", total)
            .replace("{link}", publicMenuUrl)
    }

    /** Delay aleatorio entre envios; min==max==0 -> sem espera (usado em teste). */
    private fun sleepBetween() {
        val lo = sendDelayMinSeconds.coerceAtLeast(0)
        val hi = sendDelayMaxSeconds.coerceAtLeast(lo)
        if (hi <= 0) return
        val secs = if (hi == lo) lo.toLong() else Random.nextLong(lo.toLong(), hi.toLong() + 1)
        if (secs > 0) Thread.sleep(secs * 1000)
    }

    companion object {
        const val DEFAULT_MESSAGE =
            "🛒 Olá {nome}! Você deixou itens no carrinho. Que tal finalizar seu pedido de {total}? Acesse: {link}"
    }
}

package com.menuflow.service

import com.menuflow.dto.LoyaltyStatusResponse
import com.menuflow.dto.LoyaltyTransactionResponse
import com.menuflow.event.OrderPaidEvent
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.Customer
import com.menuflow.model.LoyaltyReward
import com.menuflow.model.LoyaltyTransaction
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.LoyaltyRewardRepository
import com.menuflow.repository.tenant.LoyaltyTransactionRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * Programa de Fidelidade punch-card (Fase 3.3). Credita pontos quando um pedido é
 * pago e desbloqueia recompensas (punches) ao atingir o limite. Tudo no banco do
 * TENANT (db-per-tenant).
 *
 * O crédito é disparado pelo [OrderPaidEvent], consumido APÓS o commit da transação
 * que marcou o pedido como pago (AFTER_COMMIT) — fora da transação de cobrança, sem
 * segurar conexão, e só se a venda realmente comitou. Como o listener roda após o
 * commit, ele abre a PRÓPRIA transação de tenant (TransactionTemplate) com o
 * TenantContext vinculado a partir do slug do evento — robusto mesmo que o thread
 * já tenha perdido o contexto.
 */
@Service
class LoyaltyService(
    private val customerRepository: CustomerRepository,
    private val loyaltyTransactionRepository: LoyaltyTransactionRepository,
    private val loyaltyRewardRepository: LoyaltyRewardRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val whatsAppService: WhatsAppService,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Transação programática no banco do TENANT: o TenantContext é vinculado ANTES
    // do execute(), então a conexão roteada aterrissa no banco certo (mesmo padrão
    // do DevDataSeeder). Evita o problema de auto-invocação de @Transactional no
    // próprio bean (o listener chamaria outro método anotado sem passar pelo proxy).
    private val txTemplate = TransactionTemplate(txManager)

    /**
     * Reage ao pagamento de um pedido APÓS o commit. Fail-open total: qualquer falha
     * na fidelidade é logada e engolida — nunca compromete o fluxo de venda (o
     * pedido já está pago e comitado neste ponto).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderPaid(event: OrderPaidEvent) {
        applyOrderPaid(event)
    }

    /**
     * Núcleo do crédito de pontos, separado do listener para ser testável diretamente
     * (sem precisar de uma transação externa real que comita). Vincula o tenant do
     * evento, roda o crédito numa transação de tenant e, fora dela, dispara o aviso
     * de recompensa por WhatsApp (fail-open dentro do WhatsAppService).
     */
    fun applyOrderPaid(event: OrderPaidEvent) {
        if (event.customerId == null) return // pedido anônimo: sem fidelidade

        val previous = TenantContext.get()
        TenantContext.set(event.tenantSlug)
        try {
            val rewardDescription = txTemplate.execute { creditPoints(event) }
            // Recompensa desbloqueada -> parabeniza o cliente fora da transação.
            rewardDescription?.let { whatsAppService.sendLoyaltyReward(event.customerPhone, it) }
        } catch (e: Exception) {
            // Fail-open: a fidelidade nunca pode derrubar o pós-pagamento.
            log.error("Falha ao creditar fidelidade do pedido {}: {}", event.orderId, e.message)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /**
     * Credita os pontos do pedido pago e, se o saldo atingir o limite, desbloqueia
     * uma recompensa (debita o limite e cria o punch). Retorna a descrição da
     * recompensa quando um punch é desbloqueado (para o aviso WhatsApp), senão null.
     */
    private fun creditPoints(event: OrderPaidEvent): String? {
        val customerId = event.customerId ?: return null

        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
        if (config == null || !config.loyaltyEnabled) return null // programa desligado

        // Idempotência: o mesmo pedido nunca credita pontos duas vezes (o índice
        // parcial UNIQUE da V24 também garante isso no banco — cinto e suspensório).
        if (loyaltyTransactionRepository.existsByOrderIdAndReason(event.orderId, REASON_ORDER_PAID)) return null

        val customer = customerRepository.findById(customerId).orElse(null) ?: return null

        // Pontos = (reais gastos, truncado) * pontos por real. Dinheiro em centavos.
        val earned = (event.totalCents / 100).toInt() * config.loyaltyPointsPerReal
        if (earned <= 0) return null

        customer.loyaltyPoints += earned
        loyaltyTransactionRepository.save(
            LoyaltyTransaction(
                customerId = customerId,
                orderId = event.orderId,
                pointsDelta = earned,
                reason = REASON_ORDER_PAID,
                description = "Pontos por pedido pago",
            ),
        )

        var rewardDescription: String? = null
        val threshold = config.loyaltyRewardThreshold
        if (threshold > 0 && customer.loyaltyPoints >= threshold) {
            customer.loyaltyPoints -= threshold
            loyaltyTransactionRepository.save(
                LoyaltyTransaction(
                    customerId = customerId,
                    orderId = event.orderId,
                    pointsDelta = -threshold,
                    reason = REASON_REWARD_REDEEMED,
                    description = "Recompensa desbloqueada",
                ),
            )
            loyaltyRewardRepository.save(LoyaltyReward(customerId = customerId))
            rewardDescription = config.loyaltyRewardDescription?.ifBlank { null } ?: "Recompensa desbloqueada!"
        }

        customerRepository.save(customer)
        return rewardDescription
    }

    /** Estado de fidelidade do cliente (saldo, progresso no punch, punches, extrato). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun getCustomerLoyalty(customerId: UUID): LoyaltyStatusResponse {
        val customer = customerRepository.findById(customerId)
            .orElseThrow { ResourceNotFoundException("Cliente não encontrado: $customerId") }
        return statusOf(customer)
    }

    /**
     * Resgata uma recompensa (marca redeemedAt). ADMIN/MANAGER/CASHIER.
     *  - recompensa inexistente OU de outro cliente -> 404 (não vaza existência cross-cliente);
     *  - já resgatada -> 409.
     */
    @Transactional("tenantTransactionManager")
    fun redeemReward(customerId: UUID, rewardId: UUID) {
        val reward = loyaltyRewardRepository.findById(rewardId)
            .orElseThrow { ResourceNotFoundException("Recompensa não encontrada: $rewardId") }
        // Anti-IDOR: a recompensa precisa ser do cliente da rota; senão 404 (mesma
        // resposta de inexistente, para não revelar punches de outro cliente).
        if (reward.customerId != customerId) {
            throw ResourceNotFoundException("Recompensa não encontrada: $rewardId")
        }
        if (reward.redeemedAt != null) {
            throw ConflictException("Recompensa já resgatada")
        }
        reward.redeemedAt = Instant.now()
        loyaltyRewardRepository.save(reward)
    }

    /**
     * Ajuste manual de pontos (ADMIN/MANAGER). delta pode ser negativo; o saldo nunca
     * fica negativo (coerce >= 0). Registra um lançamento MANUAL_ADJUST no extrato.
     */
    @Transactional("tenantTransactionManager")
    fun adjustPoints(customerId: UUID, delta: Int, description: String): LoyaltyStatusResponse {
        val customer = customerRepository.findById(customerId)
            .orElseThrow { ResourceNotFoundException("Cliente não encontrado: $customerId") }
        customer.loyaltyPoints = (customer.loyaltyPoints + delta).coerceAtLeast(0)
        customerRepository.save(customer)
        loyaltyTransactionRepository.save(
            LoyaltyTransaction(
                customerId = customerId,
                orderId = null,
                pointsDelta = delta,
                reason = REASON_MANUAL_ADJUST,
                description = description,
            ),
        )
        return statusOf(customer)
    }

    /** Monta o LoyaltyStatusResponse a partir do cliente + config + extrato. */
    private fun statusOf(customer: Customer): LoyaltyStatusResponse {
        val customerId = customer.id!!
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
        val threshold = config?.loyaltyRewardThreshold ?: 0
        val progress = if (threshold > 0) customer.loyaltyPoints % threshold else customer.loyaltyPoints
        val punches = loyaltyRewardRepository.countByCustomerIdAndRedeemedAtIsNull(customerId).toInt()
        val pendingRewardId = if (punches > 0)
            loyaltyRewardRepository.findFirstByCustomerIdAndRedeemedAtIsNull(customerId)?.id
        else null
        val txs = loyaltyTransactionRepository
            .findTop10ByCustomerIdOrderByCreatedAtDesc(customerId)
            .map { LoyaltyTransactionResponse.from(it) }
        return LoyaltyStatusResponse(
            loyaltyPoints = customer.loyaltyPoints,
            rewardThreshold = threshold,
            progress = progress,
            punches = punches,
            pendingRewardId = pendingRewardId,
            transactions = txs,
        )
    }

    companion object {
        const val REASON_ORDER_PAID = "ORDER_PAID"
        const val REASON_REWARD_REDEEMED = "REWARD_REDEEMED"
        const val REASON_MANUAL_ADJUST = "MANUAL_ADJUST"
    }
}

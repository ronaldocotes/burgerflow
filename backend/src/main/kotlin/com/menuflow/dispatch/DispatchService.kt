package com.menuflow.dispatch

import com.menuflow.model.DeliveryOffer
import com.menuflow.model.DeliveryOfferStatus
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.model.TenantConfig
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.AuditLogService
import com.menuflow.service.RealtimePublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Despacho de entrega por GRUPO de WhatsApp (Fase B1). Diferente do auto-assign da
 * Fase 6.1 (que oferta ao motoboy mais proximo), aqui a oferta e um BROADCAST: nasce
 * sem motoboy definido (driver_id null, group_jid setado) e o vencedor emerge do
 * primeiro aceite ATOMICO. O timing e proposital -- a oferta so sai quando o preparo
 * esta perto de terminar (updatedAt + prepTime - leadMinutes), para o motoboy chegar
 * quando a comida estiver pronta.
 *
 * Roda no modelo db-per-tenant: os metodos publicos sao chamados pelo scheduler /
 * webhook com o TenantContext JA vinculado; a persistencia acontece em transacoes de
 * tenant abertas via TransactionTemplate (mesmo padrao do AutoAssignService), o que
 * mantem o aceite (CAS) isolado -- o perdedor da corrida afeta 0 linhas e faz
 * rollback SO daquela transacao, sem envenenar o restante do fluxo.
 *
 * O endereco do cliente NUNCA entra na oferta nem nos eventos: so trafega o bairro
 * (rotulo anonimizado). O endereco completo e revelado em DM ao vencedor na Fase B2.
 */
@Service
class DispatchService(
    private val deliveryOfferRepository: DeliveryOfferRepository,
    private val orderRepository: OrderRepository,
    private val driverRepository: DeliveryDriverRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val tenantRepository: TenantRepository,
    private val distanceService: DistanceService,
    private val ridePricingService: RidePricingService,
    private val auditLogService: AuditLogService,
    private val realtimePublisher: RealtimePublisher,
    private val eventPublisher: ApplicationEventPublisher,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    /** Dedup de aceites por messageId do WhatsApp (janela curta), anti reprocessamento. */
    private val processedMessages = ConcurrentHashMap<String, Long>()
    private val dedupTtlMillis = 5 * 60 * 1000L

    /** Resultado do aceite de uma oferta de grupo. */
    enum class AcceptOutcome { ACCEPTED, ALREADY_TAKEN, DUPLICATE, NO_ORDER, NO_OFFER }

    // -------------------------------------------------------------------------
    // Scheduler: criar ofertas devidas
    // -------------------------------------------------------------------------

    /**
     * Cria ofertas de grupo para os pedidos elegiveis cujo momento de oferta chegou.
     * Elegivel = DELIVERY pago, ainda em producao, sem motoboy e sem oferta viva. O
     * momento = updatedAt + (prepTime - leadMinutes). Retorna quantas foram criadas.
     */
    fun createDueOffers(tenantSlug: String): Int {
        val toPublish = txTemplate.execute exec@{
            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: return@exec emptyList()
            if (!config.dispatchEnabled) return@exec emptyList()
            if (config.motoboyGroupJid.isNullOrBlank()) {
                log.warn("Dispatch ligado mas sem motoboy_group_jid no tenant {} -- nada ofertado", tenantSlug)
                return@exec emptyList()
            }
            val now = Instant.now()
            val created = mutableListOf<Pair<UUID, UUID>>()
            orderRepository.findDispatchEligibleOrders().forEach { order ->
                if (offerReady(order, config, now) && noActiveOffer(order.id!!)) {
                    val offer = buildAndSaveOffer(order, config, attempt = 1)
                    created.add(offer.id!! to order.id!!)
                }
            }
            created
        } ?: emptyList()

        toPublish.forEach { (offerId, orderId) ->
            eventPublisher.publishEvent(RideOfferedEvent(tenantSlug, offerId, orderId))
        }
        return toPublish.size
    }

    // -------------------------------------------------------------------------
    // Scheduler: expirar e reofertar
    // -------------------------------------------------------------------------

    /**
     * Expira as ofertas de GRUPO vencidas e, se ainda ha tentativas, reoferta (attempt+1);
     * senao escala (log; o aviso ao dono via WhatsApp fica para a Fase B2). Retorna o
     * numero de ofertas expiradas.
     */
    fun expireAndReofferDue(tenantSlug: String): Int {
        val reoffered: Triple<Int, List<Pair<UUID, UUID>>, List<Pair<UUID, UUID>>> = txTemplate.execute exec@{
            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
                ?: return@exec Triple(0, emptyList(), emptyList())
            val now = Instant.now()
            val expired = deliveryOfferRepository.findExpiredGroupOffers(now)
            val newOffers = mutableListOf<Pair<UUID, UUID>>()
            val escalated = mutableListOf<Pair<UUID, UUID>>()
            expired.forEach { offer ->
                offer.status = DeliveryOfferStatus.EXPIRED
                offer.respondedAt = now
                deliveryOfferRepository.save(offer)

                val order = orderRepository.findById(offer.orderId).orElse(null)
                if (order != null && order.driverId == null &&
                    order.status in setOf(OrderStatus.PENDING, OrderStatus.PREPARING)
                ) {
                    if (config.dispatchEnabled &&
                        !config.motoboyGroupJid.isNullOrBlank() &&
                        offer.attempt < config.dispatchMaxAttempts
                    ) {
                        val next = buildAndSaveOffer(order, config, attempt = offer.attempt + 1)
                        newOffers.add(next.id!! to order.id!!)
                    } else if (config.dispatchEnabled && !config.motoboyGroupJid.isNullOrBlank()) {
                        // Esgotou as tentativas com o despacho ATIVO -> escala ao dono (B2).
                        escalated.add(offer.id!! to order.id!!)
                        log.warn(
                            "Pedido {} esgotou {} tentativas de despacho -- escalando ao dono",
                            order.orderNumber, config.dispatchMaxAttempts,
                        )
                    } else {
                        log.debug("Pedido {} nao reofertado: despacho desativado", order.orderNumber)
                    }
                }
            }
            Triple(expired.size, newOffers, escalated)
        } ?: Triple(0, emptyList(), emptyList())

        reoffered.second.forEach { (offerId, orderId) ->
            eventPublisher.publishEvent(RideOfferedEvent(tenantSlug, offerId, orderId))
        }
        reoffered.third.forEach { (offerId, orderId) ->
            eventPublisher.publishEvent(RideEscalatedEvent(tenantSlug, offerId, orderId))
        }
        return reoffered.first
    }

    // -------------------------------------------------------------------------
    // Webhook (B2): aceite atomico
    // -------------------------------------------------------------------------

    /**
     * O motoboy aceita a oferta pelo grupo (via poll do WhatsApp). Deduplicado por
     * messageId. O aceite e ATOMICO por CAS (compare-and-set) puro em SQL: um unico
     * UPDATE ... WHERE status='OFFERED' AND expires_at > now(). Dois motoboys que
     * respondem "quase juntos" disputam a MESMA linha; o banco serializa o UPDATE e
     * apenas UM afeta 1 linha (vence) -- o outro afeta 0 linhas (ALREADY_TAKEN). Sem
     * @Version, sem excecao de lock, sem retry: deterministico e sem envenenar a
     * transacao. Assim SEMPRE emerge exatamente um vencedor.
     */
    fun acceptOffer(acceptCode: String, driverJid: String, messageId: String): AcceptOutcome {
        if (alreadyProcessed(messageId)) return AcceptOutcome.DUPLICATE
        val tenantSlug = com.menuflow.tenant.TenantContext.getOrThrow()

        // 1. Resolve a oferta viva pelo CODIGO de aceite (o motoboy digita "ACEITO <codigo>"),
        //    depois o pedido e o motoboy (find-or-create provisional), em transacao.
        val ctx = txTemplate.execute exec@{
            val offer = deliveryOfferRepository
                .findByAcceptCodeAndStatus(acceptCode, DeliveryOfferStatus.OFFERED)
                .firstOrNull() ?: return@exec null
            val order = orderRepository.findById(offer.orderId).orElse(null)
                ?: return@exec Triple<Order?, DeliveryOffer?, DeliveryDriver?>(null, offer, null)
            val driver = resolveOrCreateDriver(driverJid, tenantSlug)
            Triple(order, offer, driver)
        }
        if (ctx == null) return AcceptOutcome.NO_OFFER
        val (order, offer, driver) = ctx
        if (offer == null) return AcceptOutcome.NO_OFFER
        if (order == null || driver == null) return AcceptOutcome.NO_ORDER

        // 2. Aceite atomico via CAS: 1 linha afetada = venceu; 0 = corrida ja fechada.
        val won = (
            txTemplate.execute {
                deliveryOfferRepository.acceptOfferAtomic(offer.id!!, driver.id!!, Instant.now())
            } ?: 0
            ) > 0
        if (!won) return AcceptOutcome.ALREADY_TAKEN

        // 3. Atribui o pedido ao vencedor + auditoria + evento (transacao propria).
        txTemplate.execute {
            val o = orderRepository.findById(order.id!!).orElse(null) ?: return@execute
            o.driverId = driver.id
            o.deliveryStatus = DeliveryStatus.ACCEPTED
            orderRepository.save(o)
            auditLogService.log(
                action = "RIDE_ACCEPTED",
                entity = "delivery_offer",
                entityId = offer.id,
                after = mapOf("orderNumber" to order.orderNumber, "driverId" to driver.id),
                actorUserId = driver.userId,
                actorRole = "DRIVER",
            )
        }

        markProcessed(messageId)
        eventPublisher.publishEvent(RideAssignedEvent(tenantSlug, offer.id!!, order.id!!, driver.id!!))
        return AcceptOutcome.ACCEPTED
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Monta e persiste uma oferta de grupo (fee/payout/distancia/bairro). */
    private fun buildAndSaveOffer(order: Order, config: TenantConfig, attempt: Int): DeliveryOffer {
        val hasGeo = config.restaurantLat != null && config.restaurantLng != null &&
            order.deliveryLat != null && order.deliveryLng != null
        val distanceMeters: Long? = if (hasGeo) {
            distanceService.getRoadDistanceMeters(
                config.restaurantLat!!, config.restaurantLng!!,
                order.deliveryLat!!, order.deliveryLng!!,
                config.distanceProvider,
            )
        } else {
            null
        }
        val feeCents = distanceMeters?.let { ridePricingService.feeCents(config, it) } ?: order.deliveryFeeCents
        val payoutCents = distanceMeters?.let { ridePricingService.payoutCents(config, it) }
            ?: (config.deliveryBasePayoutCents ?: config.deliveryBaseFeeCents)

        val offer = DeliveryOffer(
            orderId = order.id!!,
            driverId = null, // oferta de grupo nasce sem motoboy definido
            feeCents = feeCents,
            distanceKm = distanceMeters?.let { it / 1000.0 },
            expiresAt = Instant.now().plusSeconds(config.dispatchOfferTimeoutSeconds.toLong()),
        ).apply {
            this.payoutCents = payoutCents
            this.distanceMeters = distanceMeters
            this.neighborhoodLabel = order.deliveryNeighborhood?.take(60)
            this.groupJid = config.motoboyGroupJid
            this.attempt = attempt
            this.acceptCode = generateAcceptCode()
        }
        return deliveryOfferRepository.save(offer)
    }

    /** Codigo curto (8 chars A-Z0-9) que o motoboy digita no grupo para aceitar. */
    private fun generateAcceptCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // sem I,O,0,1 (ambiguidade)
        return (1..8).map { alphabet[kotlin.random.Random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Momento de ofertar chegou? updatedAt + (prepTime - leadMinutes) <= agora. */
    private fun offerReady(order: Order, config: TenantConfig, now: Instant): Boolean {
        val leadSeconds = (order.estimatedPrepTimeMinutes - config.dispatchReadyLeadMinutes) * 60L
        val offerAt = order.updatedAt.plusSeconds(leadSeconds)
        return !offerAt.isAfter(now)
    }

    private fun noActiveOffer(orderId: UUID): Boolean =
        deliveryOfferRepository.findByOrderIdAndStatus(orderId, DeliveryOfferStatus.OFFERED).isEmpty()

    /** Motoboy pelo telefone extraido do JID; cria um PROVISIONAL se desconhecido. */
    private fun resolveOrCreateDriver(jid: String, tenantSlug: String): DeliveryDriver {
        val phone = jid.substringBefore('@').filter { it.isDigit() }
        driverRepository.findByPhone(phone)?.let { return it }
        val tenantId = tenantRepository.findBySlug(tenantSlug)?.id
            ?: throw IllegalStateException("Tenant nao encontrado para provisionar motoboy: $tenantSlug")
        return driverRepository.save(
            DeliveryDriver(
                name = "Motoboy ${phone.takeLast(4)}",
                phone = phone,
                active = true,
                activeShift = true,
                tenantId = tenantId,
                driverType = "FREELANCER",
                provisional = true,
                signupToken = UUID.randomUUID(),
                // M2: o link de auto-cadastro expira (72h); NULL = invalido (fail-closed).
                signupTokenExpiresAt = Instant.now().plusSeconds(72 * 3600L),
            ),
        )
    }

    private fun alreadyProcessed(messageId: String): Boolean {
        pruneDedup()
        return processedMessages.containsKey(messageId)
    }

    private fun markProcessed(messageId: String) {
        processedMessages[messageId] = System.currentTimeMillis()
    }

    private fun pruneDedup() {
        val cutoff = System.currentTimeMillis() - dedupTtlMillis
        processedMessages.entries.removeIf { it.value < cutoff }
    }
}

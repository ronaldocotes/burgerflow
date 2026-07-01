package com.menuflow.service

import com.menuflow.delivery.DeliveryFeeCalculator
import com.menuflow.delivery.HaversineUtil
import com.menuflow.dto.DeliveryOfferResponse
import com.menuflow.event.OrderPaidEvent
import com.menuflow.model.DeliveryOffer
import com.menuflow.model.OrderType
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.OrderRepository
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

/**
 * Auto-assign de entrega (Fase 6.1). Quando um pedido de DELIVERY com geocode fica
 * PAGO, oferta a entrega ao motoboy ONLINE mais proximo (Haversine) dentro do raio
 * configurado, criando uma DeliveryOffer OFFERED e publicando-a via STOMP.
 *
 * Assim como a fidelidade e a recuperacao de carrinho, reage ao [OrderPaidEvent]
 * APOS o commit (AFTER_COMMIT): fora da transacao de cobranca, so se a venda comitou,
 * e abrindo a PROPRIA transacao de tenant (TransactionTemplate) com o TenantContext
 * vinculado a partir do slug do evento — robusto pos-commit no modelo db-per-tenant.
 * Fail-open total: qualquer falha no despacho e logada e engolida, nunca compromete
 * o pedido ja pago.
 */
@Service
class AutoAssignService(
    private val deliveryDriverRepository: DeliveryDriverRepository,
    private val deliveryOfferRepository: DeliveryOfferRepository,
    private val orderRepository: OrderRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val feeCalculator: DeliveryFeeCalculator,
    private val realtimePublisher: RealtimePublisher,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    /** Reage ao pagamento do pedido APOS o commit; delega para [applyOrderPaid]. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderPaid(event: OrderPaidEvent) {
        applyOrderPaid(event)
    }

    /**
     * Nucleo do gatilho, separado do listener para ser testavel direto. Vincula o
     * tenant do evento, carrega o pedido e — se for entrega com geocode — tenta o
     * auto-assign. Fail-open.
     */
    fun applyOrderPaid(event: OrderPaidEvent) {
        val previous = TenantContext.get()
        TenantContext.set(event.tenantSlug)
        try {
            val order = txTemplate.execute { orderRepository.findById(event.orderId).orElse(null) }
                ?: return
            if (order.orderType != OrderType.DELIVERY) return
            if (order.deliveryLat == null || order.deliveryLng == null) return
            tryAutoAssign(order, event.tenantSlug)
        } catch (e: Exception) {
            log.error("Falha no auto-assign do pedido {}: {}", event.orderId, e.message)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /**
     * Oferta a entrega ao motoboy mais proximo. Vincula o tenant, roda numa transacao
     * de tenant a selecao (config + entregadores disponiveis dentro do raio + oferta)
     * e, fora dela, publica a oferta via STOMP. Retorna a oferta criada ou null quando
     * nao ha auto-assign a fazer (desligado, sem geocode, sem motoboy no raio).
     */
    fun tryAutoAssign(order: com.menuflow.model.Order, tenantSlug: String): DeliveryOffer? {
        val orderLat = order.deliveryLat ?: return null
        val orderLng = order.deliveryLng ?: return null

        val previous = TenantContext.get()
        TenantContext.set(tenantSlug)
        try {
            val result = txTemplate.execute exec@{
                val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
                    ?: return@exec null
                if (!config.autoAssignEnabled) return@exec null

                // Motoboys online, ociosos e DENTRO do raio maximo (linha reta ao ponto de entrega).
                val available = deliveryDriverRepository.findAvailableWithLocation()
                    .filter { it.lastLat != null && it.lastLng != null }
                    .filter {
                        HaversineUtil.distanceKm(it.lastLat!!, it.lastLng!!, orderLat, orderLng) <= config.maxOfferRadiusKm
                    }
                if (available.isEmpty()) return@exec null

                val nearest = available.minByOrNull {
                    HaversineUtil.distanceKm(it.lastLat!!, it.lastLng!!, orderLat, orderLng)
                } ?: return@exec null

                val distanceKm = HaversineUtil.estimatedRoadKm(
                    nearest.lastLat!!, nearest.lastLng!!, orderLat, orderLng,
                )
                val feeCents = feeCalculator.calculate(distanceKm, config)

                val offer = deliveryOfferRepository.save(
                    DeliveryOffer(
                        orderId = order.id!!,
                        driverId = nearest.id!!,
                        feeCents = feeCents,
                        distanceKm = distanceKm,
                        expiresAt = Instant.now().plusSeconds(config.offerTimeoutSeconds.toLong()),
                    ),
                )
                Pair(nearest.id!!, offer)
            }

            result?.let { (driverId, offer) ->
                realtimePublisher.publishDeliveryOffer(tenantSlug, driverId, DeliveryOfferResponse.from(offer))
            }
            return result?.second
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /**
     * Expira as ofertas OFFERED cujo prazo passou (chamado pelo job por tenant, com o
     * TenantContext ja vinculado). Retorna quantas foram expiradas. A cascata para o
     * proximo motoboy fica para a Fase 6.2.
     */
    @Transactional("tenantTransactionManager")
    fun expireStaleOffers(): Int {
        val expired = deliveryOfferRepository.findExpiredOffers(Instant.now())
        expired.forEach {
            it.status = com.menuflow.model.DeliveryOfferStatus.EXPIRED
            it.respondedAt = Instant.now()
        }
        if (expired.isNotEmpty()) deliveryOfferRepository.saveAll(expired)
        return expired.size
    }
}

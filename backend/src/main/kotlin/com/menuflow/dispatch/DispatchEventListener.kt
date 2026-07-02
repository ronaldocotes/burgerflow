package com.menuflow.dispatch

import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Consome os eventos do despacho (Fase B2) e dispara o WhatsApp. Roda @Async: o thread
 * do scheduler / do webhook NAO fica preso na chamada HTTP ao WAHA.
 *
 * db-per-tenant: como @Async troca de thread, o TenantContext (ThreadLocal) NAO e
 * propagado — cada handler REVINCULA o slug vindo do evento antes de tocar o banco do
 * tenant e limpa no fim. As entidades sao carregadas dentro de uma transacao de tenant
 * ([txTemplate]); as chamadas ao WAHA acontecem DEPOIS do commit (fora da tx), para nao
 * segurar conexao de banco durante o HTTP externo.
 *
 * Os eventos ja sao publicados pelo DispatchService APOS o commit das suas transacoes
 * (as chamadas publishEvent ficam apos txTemplate.execute retornar), entao um @EventListener
 * simples basta — nao e preciso @TransactionalEventListener aqui.
 */
@Component
class DispatchEventListener(
    private val offerRepository: DeliveryOfferRepository,
    private val orderRepository: OrderRepository,
    private val driverRepository: DeliveryDriverRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val dispatchWhatsAppService: DispatchWhatsAppService,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    @Async
    @EventListener
    fun onRideOffered(event: RideOfferedEvent) = withTenant(event.tenantSlug) {
        val loaded = txTemplate.execute {
            val offer = offerRepository.findById(event.offerId).orElse(null)
            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
            if (offer != null && config != null) offer to config else null
        }
        loaded?.let { (offer, config) -> dispatchWhatsAppService.sendGroupOffer(offer, config, event.tenantSlug) }
    }

    @Async
    @EventListener
    fun onRideAssigned(event: RideAssignedEvent) = withTenant(event.tenantSlug) {
        val ctx = txTemplate.execute {
            val offer = offerRepository.findById(event.offerId).orElse(null) ?: return@execute null
            val order = orderRepository.findById(event.orderId).orElse(null) ?: return@execute null
            val driver = driverRepository.findById(event.driverId).orElse(null) ?: return@execute null
            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: return@execute null
            Assigned(offer, order, driver, config)
        } ?: return@withTenant
        // Fora da tx (HTTP ao WAHA): DM ao vencedor + ACK no grupo + aviso ao cliente.
        dispatchWhatsAppService.sendDmToWinner(ctx.offer, ctx.driver, ctx.order, ctx.config)
        dispatchWhatsAppService.sendGroupAck(ctx.offer, ctx.driver, ctx.config)
        dispatchWhatsAppService.sendClientNotification(ctx.order, ctx.config)

        // Recrutamento: freelancer provisional que JA concluiu a 1a entrega e ainda nao foi
        // convidado. first_delivery_at e carimbado no DELIVERED (fora do escopo da B2), entao
        // este convite so dispara em corridas POSTERIORES a primeira entrega concluida.
        if (ctx.driver.provisional && ctx.driver.firstDeliveryAt != null && ctx.driver.recruitmentSentAt == null) {
            dispatchWhatsAppService.sendRecruitmentDm(ctx.driver, ctx.config)
            txTemplate.execute {
                driverRepository.findById(event.driverId).orElse(null)?.let {
                    it.recruitmentSentAt = Instant.now()
                    driverRepository.save(it)
                }
            }
        }
    }

    @Async
    @EventListener
    fun onRideEscalated(event: RideEscalatedEvent) = withTenant(event.tenantSlug) {
        val ctx = txTemplate.execute {
            val offer = offerRepository.findById(event.offerId).orElse(null) ?: return@execute null
            val order = orderRepository.findById(event.orderId).orElse(null) ?: return@execute null
            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: return@execute null
            Triple(offer, order, config)
        } ?: return@withTenant
        dispatchWhatsAppService.escalateToOwner(ctx.first, ctx.second, ctx.third)
    }

    /** Revincula o TenantContext do slug (perdido no salto @Async) e restaura no fim. */
    private inline fun withTenant(slug: String, block: () -> Unit) {
        val previous = TenantContext.get()
        TenantContext.set(slug)
        try {
            block()
        } catch (e: Exception) {
            log.error("Falha ao enviar WhatsApp do despacho (tenant {}): {}", slug, e.message)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private data class Assigned(
        val offer: com.menuflow.model.DeliveryOffer,
        val order: com.menuflow.model.Order,
        val driver: com.menuflow.model.DeliveryDriver,
        val config: com.menuflow.model.TenantConfig,
    )
}

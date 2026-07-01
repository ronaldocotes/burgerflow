package com.menuflow.service

import com.menuflow.dto.AssignDriverRequest
import com.menuflow.dto.DeliveryOfferResponse
import com.menuflow.dto.DeliveryOrderResponse
import com.menuflow.dto.DeliveryStatusUpdateRequest
import com.menuflow.dto.DriverCreateRequest
import com.menuflow.dto.DriverLocationEvent
import com.menuflow.dto.DriverResponse
import com.menuflow.dto.LocationUpdateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryOfferStatus
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.OrderType
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.security.AuthPrincipal
import com.menuflow.security.SecurityUtils
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Delivery dispatch (Sprint 2). Drivers and dispatch state live in the TENANT
 * database (already tenant-scoped by the routed datasource). Status changes are
 * broadcast to /topic/delivery/{tenantSlug}.
 */
@Service
class DeliveryService(
    private val driverRepository: DeliveryDriverRepository,
    private val orderRepository: OrderRepository,
    private val offerRepository: DeliveryOfferRepository,
    private val realtimePublisher: RealtimePublisher,
    private val tenantConfigRepository: com.menuflow.repository.tenant.TenantConfigRepository,
    private val eventPublisher: org.springframework.context.ApplicationEventPublisher,
) {
    private val saoPaulo = ZoneId.of("America/Sao_Paulo")

    @Transactional("tenantTransactionManager")
    fun createDriver(req: DriverCreateRequest): DriverResponse {
        val tenantUuid = SecurityUtils.currentPrincipalOrThrow().tenantUuid
        val saved = driverRepository.save(
            DeliveryDriver(
                name = req.name.trim(),
                phone = req.phone.trim(),
                licensePlate = req.licensePlate?.trim()?.uppercase(),
                tenantId = tenantUuid,
            ),
        )
        return DriverResponse.from(saved)
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun listActiveDrivers(): List<DriverResponse> =
        driverRepository.findByActiveTrueOrderByNameAsc().map { DriverResponse.from(it) }

    /**
     * Assigns a courier to a delivery order and sets deliveryStatus = ASSIGNED.
     * Guards: the order must be a DELIVERY order; the driver must exist and be
     * active. Publishes the dispatch to the tenant delivery topic.
     */
    @Transactional("tenantTransactionManager")
    fun assign(orderId: UUID, req: AssignDriverRequest): DeliveryOrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }
        if (order.orderType != OrderType.DELIVERY) {
            throw BusinessException("Order ${order.orderNumber} is not a delivery order")
        }
        val driver = driverRepository.findById(req.driverId)
            .orElseThrow { ResourceNotFoundException("Driver not found: ${req.driverId}") }
        if (!driver.active) {
            throw BusinessException("Driver ${driver.name} is inactive")
        }

        order.driverId = driver.id
        order.deliveryStatus = DeliveryStatus.ASSIGNED
        val saved = orderRepository.save(order)

        val payload = DeliveryOrderResponse.from(saved)
        realtimePublisher.publishDelivery(currentTenantSlug(), payload)
        return payload
    }

    /**
     * Courier updates dispatch status (ASSIGNED -> OUT_FOR_DELIVERY -> DELIVERED).
     * Order must already be assigned to a driver. Publishes to the delivery topic.
     */
    @Transactional("tenantTransactionManager")
    fun updateStatus(orderId: UUID, req: DeliveryStatusUpdateRequest): DeliveryOrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }
        val current = order.deliveryStatus
            ?: throw BusinessException("Order ${order.orderNumber} has not been assigned to a driver")
        validateTransition(current, req.deliveryStatus)

        order.deliveryStatus = req.deliveryStatus
        val saved = orderRepository.save(order)

        val payload = DeliveryOrderResponse.from(saved)
        realtimePublisher.publishDelivery(currentTenantSlug(), payload)
        // Notificacao WhatsApp (Fase 2.4): so o despacho "saiu para entrega" avisa
        // por aqui — a entrega final (DELIVERED) e notificada pela mudanca de status
        // de cozinha (OrderService), evitando aviso duplicado de "entregue". Publica
        // um fato de dominio consumido APOS o commit pelo WhatsAppService.
        if (req.deliveryStatus == DeliveryStatus.OUT_FOR_DELIVERY) {
            val restaurantName = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
                ?.restaurantName ?: "o restaurante"
            eventPublisher.publishEvent(
                OrderStatusNotification(
                    saved.customerPhone,
                    OrderNotificationKind.OUT_FOR_DELIVERY,
                    restaurantName,
                ),
            )
        }
        return payload
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun activeDeliveryOrders(): List<DeliveryOrderResponse> {
        val from = LocalDate.now(saoPaulo).atStartOfDay(saoPaulo).toInstant()
        return orderRepository.findActiveDeliveryOrders(from).map { DeliveryOrderResponse.from(it) }
    }

    private fun validateTransition(current: DeliveryStatus, next: DeliveryStatus) {
        // Fluxo legado (Sprint 2) mantido: ASSIGNED -> OUT_FOR_DELIVERY -> DELIVERED.
        // Fluxo do motoboy (Fase 6.1) adicionado de forma compativel. FAILED e um
        // estado de falha alcancavel de qualquer etapa nao-terminal.
        val allowed = when (current) {
            DeliveryStatus.PENDING -> setOf(DeliveryStatus.OFFERED, DeliveryStatus.ASSIGNED, DeliveryStatus.FAILED)
            DeliveryStatus.OFFERED -> setOf(DeliveryStatus.ACCEPTED, DeliveryStatus.PENDING, DeliveryStatus.FAILED)
            DeliveryStatus.ACCEPTED -> setOf(
                DeliveryStatus.ARRIVED_AT_STORE, DeliveryStatus.PICKED_UP,
                DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.FAILED,
            )
            DeliveryStatus.ARRIVED_AT_STORE -> setOf(DeliveryStatus.PICKED_UP, DeliveryStatus.FAILED)
            DeliveryStatus.PICKED_UP -> setOf(
                DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.ARRIVED_AT_CUSTOMER, DeliveryStatus.FAILED,
            )
            DeliveryStatus.ASSIGNED -> setOf(
                DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.ARRIVED_AT_STORE, DeliveryStatus.FAILED,
            )
            DeliveryStatus.OUT_FOR_DELIVERY -> setOf(
                DeliveryStatus.DELIVERED, DeliveryStatus.ARRIVED_AT_CUSTOMER, DeliveryStatus.FAILED,
            )
            DeliveryStatus.ARRIVED_AT_CUSTOMER -> setOf(DeliveryStatus.DELIVERED, DeliveryStatus.FAILED)
            DeliveryStatus.DELIVERED -> emptySet()
            DeliveryStatus.FAILED -> emptySet()
        }
        if (next !in allowed) {
            throw BusinessException("Invalid delivery status transition from $current to $next")
        }
    }

    // ---------------------------------------------------------------------------
    // Fase 6.1 — turno, localizacao e ofertas do app do motoboy
    // ---------------------------------------------------------------------------

    private val fleetRoles = setOf("ADMIN", "MANAGER", "OPERATOR")

    /**
     * Liga/desliga o turno de um entregador. Um gestor (ADMIN/MANAGER/OPERATOR) pode
     * mexer no turno de qualquer entregador da frota; um DRIVER so pode mexer no
     * PROPRIO (anti-IDOR: o driver da rota tem de estar ligado ao user do token).
     */
    @Transactional("tenantTransactionManager")
    fun setShift(driverId: UUID, activeShift: Boolean): DriverResponse {
        val principal = SecurityUtils.currentPrincipalOrThrow()
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResourceNotFoundException("Entregador nao encontrado: $driverId") }
        authorizeDriverAction(principal, driver)
        driver.activeShift = activeShift
        return DriverResponse.from(driverRepository.save(driver))
    }

    /**
     * O motoboy reporta sua posicao (GPS). Resolve o entregador pelo user do token
     * assinado (nunca por id do corpo — anti-IDOR), atualiza a ultima localizacao e
     * publica a posicao ao vivo no topico de entrega do tenant.
     */
    @Transactional("tenantTransactionManager")
    fun updateLocation(req: LocationUpdateRequest): DriverResponse {
        val driver = currentDriverOrThrow()
        driver.lastLat = req.lat
        driver.lastLng = req.lng
        driver.batteryPct = req.batteryPct
        driver.lastLocationAt = Instant.now()
        val saved = driverRepository.save(driver)
        realtimePublisher.publishDriverLocation(
            currentTenantSlug(),
            DriverLocationEvent(saved.id!!, req.lat, req.lng, req.batteryPct, saved.lastLocationAt!!),
        )
        return DriverResponse.from(saved)
    }

    /**
     * O motoboy aceita uma oferta. So o DONO da oferta pode aceita-la (anti-IDOR). A
     * oferta precisa estar OFFERED e dentro do prazo; senao 409. Ao aceitar: marca a
     * oferta ACCEPTED, atribui o pedido ao entregador e move o deliveryStatus para
     * ACCEPTED. Publica o despacho no topico de entrega.
     */
    @Transactional("tenantTransactionManager")
    fun acceptOffer(offerId: UUID): DeliveryOfferResponse {
        val driver = currentDriverOrThrow()
        val offer = offerRepository.findById(offerId)
            .orElseThrow { ResourceNotFoundException("Oferta nao encontrada: $offerId") }
        // Anti-IDOR: a oferta tem de ser deste motoboy.
        if (offer.driverId != driver.id) {
            throw AccessDeniedException("Oferta de outro entregador")
        }
        if (offer.status != DeliveryOfferStatus.OFFERED) {
            throw ConflictException("Oferta nao esta mais disponivel (status ${offer.status})")
        }
        if (offer.expiresAt.isBefore(Instant.now())) {
            offer.status = DeliveryOfferStatus.EXPIRED
            offer.respondedAt = Instant.now()
            offerRepository.save(offer)
            throw ConflictException("Oferta expirada")
        }

        offer.status = DeliveryOfferStatus.ACCEPTED
        offer.respondedAt = Instant.now()
        offerRepository.save(offer)

        val order = orderRepository.findById(offer.orderId)
            .orElseThrow { ResourceNotFoundException("Pedido nao encontrado: ${offer.orderId}") }
        order.driverId = driver.id
        order.deliveryStatus = DeliveryStatus.ACCEPTED
        val savedOrder = orderRepository.save(order)

        realtimePublisher.publishDelivery(currentTenantSlug(), DeliveryOrderResponse.from(savedOrder))
        return DeliveryOfferResponse.from(offer)
    }

    /**
     * O motoboy recusa uma oferta (so o dono). Marca REJECTED. A cascata para o
     * proximo entregador fica para a Fase 6.2.
     */
    @Transactional("tenantTransactionManager")
    fun rejectOffer(offerId: UUID): DeliveryOfferResponse {
        val driver = currentDriverOrThrow()
        val offer = offerRepository.findById(offerId)
            .orElseThrow { ResourceNotFoundException("Oferta nao encontrada: $offerId") }
        if (offer.driverId != driver.id) {
            throw AccessDeniedException("Oferta de outro entregador")
        }
        if (offer.status != DeliveryOfferStatus.OFFERED) {
            throw ConflictException("Oferta nao esta mais disponivel (status ${offer.status})")
        }
        offer.status = DeliveryOfferStatus.REJECTED
        offer.respondedAt = Instant.now()
        return DeliveryOfferResponse.from(offerRepository.save(offer))
    }

    /** Pedidos de entrega ATIVOS atribuidos ao motoboy logado. */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun myOrders(): List<DeliveryOrderResponse> {
        val driver = currentDriverOrThrow()
        return orderRepository.findActiveOrdersForDriver(driver.id!!).map { DeliveryOrderResponse.from(it) }
    }

    /** Entregador ligado ao user do token assinado; 403 se o user nao for um motoboy. */
    private fun currentDriverOrThrow(): DeliveryDriver {
        val principal = SecurityUtils.currentPrincipalOrThrow()
        return driverRepository.findByUserId(principal.userId)
            ?: throw AccessDeniedException("Usuario nao esta vinculado a um entregador")
    }

    /** Gestor mexe em qualquer motoboy; DRIVER so no proprio (elo user_id). */
    private fun authorizeDriverAction(principal: AuthPrincipal, driver: DeliveryDriver) {
        val isFleetManager = principal.roles.any { it in fleetRoles }
        if (isFleetManager) return
        if (driver.userId != principal.userId) {
            throw AccessDeniedException("Entregador so pode alterar o proprio turno")
        }
    }

    /** Tenant slug from the signed principal (authoritative), for the topic name. */
    private fun currentTenantSlug(): String = SecurityUtils.currentPrincipalOrThrow().tenantSlug
}

package com.menuflow.service

import com.menuflow.dto.AssignDriverRequest
import com.menuflow.dto.DeliveryOfferResponse
import com.menuflow.dto.DeliveryOrderResponse
import com.menuflow.dto.DeliveryStatusUpdateRequest
import com.menuflow.dto.DriverCreateRequest
import com.menuflow.dto.DriverEarningsResponse
import com.menuflow.dto.DriverLocationEvent
import com.menuflow.dto.DriverMeResponse
import com.menuflow.dto.DriverResponse
import com.menuflow.dto.LocationUpdateRequest
import com.menuflow.dto.OrderStatusUpdateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryOfferStatus
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.model.control.UserRole
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.DriverConfigRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.security.AuthPrincipal
import com.menuflow.security.SecurityUtils
import org.springframework.dao.DataIntegrityViolationException
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
    // Fase 6.2 (app do motoboy): config de remuneracao (ganhos) e vinculo com o
    // usuario do banco de CONTROLE (o repo de controle tem seu proprio EMF/TM,
    // entao le fora da transacao do tenant sem conflito).
    private val driverConfigRepository: DriverConfigRepository,
    private val controlUserRepository: com.menuflow.repository.control.UserRepository,
    private val orderService: OrderService,
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
        // Atribuicao single (fora de rota multipla, issue #4): limpa qualquer sequencia
        // stale de uma rota anterior — senao o app mostraria uma "parada N" fantasma.
        order.deliverySequence = null
        val saved = orderRepository.save(order)

        val payload = DeliveryOrderResponse.from(saved)
        realtimePublisher.publishDelivery(currentTenantSlug(), payload)
        return payload
    }

    /**
     * Atualiza o status do despacho (FSM em [validateTransition]).
     *
     * Anti-BOLA (auditoria A1): um DRIVER "puro" (sem papel de gestao) so avanca a
     * PROPRIA entrega — o pedido precisa estar carimbado com o driver ligado ao user
     * do token (order.driverId == driver do JWT); senao 403. Gestores
     * (ADMIN/MANAGER/OPERATOR) seguem podendo mexer em qualquer despacho do tenant.
     *
     * Idempotencia p/ retry do app: repetir o MESMO status alvo e no-op (devolve o
     * estado atual, sem re-publicar nem re-notificar) — reenvio por rede instavel
     * nao vira 400 de transicao invalida.
     *
     * Ao virar DELIVERED: (a) se a cozinha ja marcou READY, promove o pedido a
     * DELIVERED via OrderService (fonte unica do FSM de cozinha, completedAt, KDS e
     * notificacao WhatsApp) — e esse carimbo (status DELIVERED + completedAt) que o
     * acerto financeiro e o /earnings/my contam; (b) carimba firstDeliveryAt do
     * entregador (gatilho do funil de recrutamento do freelancer, Fase B2/C1).
     */
    @Transactional("tenantTransactionManager")
    fun updateStatus(orderId: UUID, req: DeliveryStatusUpdateRequest): DeliveryOrderResponse {
        val principal = SecurityUtils.currentPrincipalOrThrow()
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException("Order not found: $orderId") }
        val current = order.deliveryStatus
            ?: throw BusinessException("Order ${order.orderNumber} has not been assigned to a driver")

        // Anti-BOLA: quem nao e gestor so age na propria entrega.
        if (principal.roles.none { it in fleetRoles }) {
            val driver = currentDriverOrThrow()
            if (order.driverId != driver.id) {
                throw AccessDeniedException("Entrega de outro entregador")
            }
        }

        if (req.deliveryStatus == current) {
            // Retry idempotente: mesmo alvo, nada a fazer.
            return DeliveryOrderResponse.from(order)
        }
        validateTransition(current, req.deliveryStatus)

        order.deliveryStatus = req.deliveryStatus
        var saved = orderRepository.save(order)

        if (req.deliveryStatus == DeliveryStatus.DELIVERED) {
            if (saved.status == OrderStatus.READY) {
                // READY -> DELIVERED e transicao valida do FSM de cozinha; delegar ao
                // OrderService evita duplicar completedAt/KDS/notificacao aqui.
                orderService.updateStatus(saved.id!!, OrderStatusUpdateRequest(status = OrderStatus.DELIVERED))
                saved = orderRepository.findById(saved.id!!).orElse(saved)
            }
            saved.driverId?.let { driverId ->
                driverRepository.findById(driverId).orElse(null)?.let { d ->
                    if (d.firstDeliveryAt == null) {
                        d.firstDeliveryAt = Instant.now()
                        driverRepository.save(d)
                    }
                }
            }
        }

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
     *
     * LGPD (auditoria B1): fora de turno o app nao coleta posicao — 409 orienta o
     * app a ligar o turno antes de enviar GPS.
     */
    @Transactional("tenantTransactionManager")
    fun updateLocation(req: LocationUpdateRequest): DriverResponse {
        val driver = currentDriverOrThrow()
        if (!driver.activeShift) {
            throw ConflictException("Turno inativo: ligue o turno para enviar localizacao")
        }
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

    // ---------------------------------------------------------------------------
    // Fase 6.2 — perfil, ofertas pendentes, ganhos e vinculo user<->driver
    // ---------------------------------------------------------------------------

    /**
     * Perfil do motoboy logado (app): dados basicos + turno + config de remuneracao
     * (o motoboy VE o proprio combinado; quem EDITA e o gestor via /drivers/{id}/config).
     * Resolve SEMPRE pelo user do token assinado — nunca por id de fora.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun me(): DriverMeResponse {
        val driver = currentDriverOrThrow()
        val config = driverConfigRepository.findByDriverId(driver.id!!)
        return DriverMeResponse.from(driver, config)
    }

    /** Liga/desliga o turno do PROPRIO motoboy logado (app), sem id na rota. */
    @Transactional("tenantTransactionManager")
    fun setOwnShift(activeShift: Boolean): DriverResponse {
        val driver = currentDriverOrThrow()
        driver.activeShift = activeShift
        return DriverResponse.from(driverRepository.save(driver))
    }

    /**
     * Ofertas PENDENTES do motoboy logado (status OFFERED e ainda dentro do prazo),
     * mais proxima de expirar primeiro. Ofertas de grupo (driverId nulo ate o aceite)
     * nao entram — o aceite delas acontece pelo WhatsApp (Fase B2).
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun myOffers(): List<DeliveryOfferResponse> {
        val driver = currentDriverOrThrow()
        val now = Instant.now()
        return offerRepository.findByDriverIdAndStatus(driver.id!!, DeliveryOfferStatus.OFFERED)
            .filter { it.expiresAt.isAfter(now) }
            .sortedBy { it.expiresAt }
            .map { DeliveryOfferResponse.from(it) }
    }

    /**
     * Ganhos do motoboy logado no periodo [from, to] (datas em America/Sao_Paulo,
     * default: hoje). Conta as MESMAS entregas do acerto financeiro
     * (countDeliveriesByDriverAndPeriod: status DELIVERED + completedAt na janela) e
     * aplica a config de remuneracao — assim o que o app mostra bate com o que o
     * restaurante paga. Sem config: contagem com valores zerados e hasConfig=false.
     * A diaria e o km sao informativos (dias trabalhados/km sao apurados no acerto).
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun myEarnings(from: LocalDate?, to: LocalDate?): DriverEarningsResponse {
        val driver = currentDriverOrThrow()
        val today = LocalDate.now(saoPaulo)
        val start = from ?: today
        val end = to ?: today
        if (end.isBefore(start)) {
            throw BusinessException("Periodo invalido: fim antes do inicio")
        }
        if (start.plusDays(366).isBefore(end)) {
            throw BusinessException("Periodo maximo de 366 dias")
        }
        val fromInstant = start.atStartOfDay(saoPaulo).toInstant()
        val toInstant = end.plusDays(1).atStartOfDay(saoPaulo).toInstant()
        val deliveries = orderRepository.countDeliveriesByDriverAndPeriod(driver.id!!, fromInstant, toInstant)
        val config = driverConfigRepository.findByDriverId(driver.id!!)
        return DriverEarningsResponse(
            from = start,
            to = end,
            deliveriesCount = deliveries,
            deliveryEarningsCents = deliveries * (config?.perDeliveryCents ?: 0L),
            perDeliveryCents = config?.perDeliveryCents ?: 0L,
            dailyRateCents = config?.dailyRateCents ?: 0L,
            perKmCents = config?.perKmCents ?: 0L,
            hasConfig = config != null,
        )
    }

    /**
     * Vincula (ou desvincula, userId nulo) o entregador a um usuario do banco de
     * CONTROLE com papel DRIVER — e este elo que permite o login do app resolver o
     * driver do tenant ([currentDriverOrThrow]). So gestao chama (RBAC no controller).
     *
     * Guardas: o usuario tem de existir, estar ativo e pertencer AO MESMO tenant do
     * token (404 generico — nao vaza existencia de usuario de outro restaurante) e
     * ter papel DRIVER (400). O indice UNICO parcial da V35 (user_id) barra o mesmo
     * usuario em dois entregadores: corrida vira 409.
     */
    @Transactional("tenantTransactionManager")
    fun linkDriverUser(driverId: UUID, userId: UUID?): DriverResponse {
        val principal = SecurityUtils.currentPrincipalOrThrow()
        val driver = driverRepository.findById(driverId)
            .orElseThrow { ResourceNotFoundException("Entregador nao encontrado: $driverId") }
        if (userId == null) {
            driver.userId = null
            return DriverResponse.from(driverRepository.save(driver))
        }
        val user = controlUserRepository.findById(userId).orElse(null)
            ?.takeIf { it.tenantId == principal.tenantUuid && it.isActive }
            ?: throw ResourceNotFoundException("Usuario nao encontrado neste restaurante")
        if (user.role != UserRole.DRIVER) {
            throw BusinessException("Usuario precisa ter o papel DRIVER para ser vinculado a um entregador")
        }
        driver.userId = userId
        return try {
            // saveAndFlush: forca o INSERT/UPDATE agora para o indice unico parcial
            // (uq_delivery_drivers_user_id) acusar o conflito DENTRO deste metodo.
            DriverResponse.from(driverRepository.saveAndFlush(driver))
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("Usuario ja vinculado a outro entregador")
        }
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

package com.menuflow.dto

import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryOffer
import com.menuflow.model.DeliveryOfferStatus
import com.menuflow.model.DeliveryStatus
import com.menuflow.model.DriverConfig
import com.menuflow.model.Order
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DriverCreateRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val phone: String,
    val licensePlate: String? = null,
)

data class DriverResponse(
    val id: UUID,
    val name: String,
    val phone: String,
    val licensePlate: String?,
    val active: Boolean,
    val activeShift: Boolean,
    val lastLat: Double?,
    val lastLng: Double?,
    val lastLocationAt: Instant?,
    val batteryPct: Int?,
    /** User (banco de controle, papel DRIVER) vinculado; null = sem acesso ao app. */
    val userId: UUID?,
) {
    companion object {
        fun from(d: DeliveryDriver) = DriverResponse(
            id = d.id!!,
            name = d.name,
            phone = d.phone,
            licensePlate = d.licensePlate,
            active = d.active,
            activeShift = d.activeShift,
            lastLat = d.lastLat,
            lastLng = d.lastLng,
            lastLocationAt = d.lastLocationAt,
            batteryPct = d.batteryPct,
            userId = d.userId,
        )
    }
}

/** Fase 6.1 — motoboy liga/desliga o turno. Estado explícito (idempotente p/ retry). */
data class ShiftRequest(
    @field:NotNull val activeShift: Boolean,
)

/** Fase 6.1 — envio de GPS pelo app do motoboy. lat/lng obrigatórios; bateria opcional. */
data class LocationUpdateRequest(
    @field:NotNull @field:DecimalMin("-90.0") @field:DecimalMax("90.0")
    val lat: Double,
    @field:NotNull @field:DecimalMin("-180.0") @field:DecimalMax("180.0")
    val lng: Double,
    @field:Min(0) @field:Max(100)
    val batteryPct: Int? = null,
)

/** Fase 6.1 — payload STOMP da posição ao vivo do entregador. */
data class DriverLocationEvent(
    val driverId: UUID,
    val lat: Double,
    val lng: Double,
    val batteryPct: Int?,
    val at: Instant,
)

/** Fase 6.1 — oferta de entrega (resposta REST + payload STOMP para o app do motoboy). */
data class DeliveryOfferResponse(
    val id: UUID,
    val orderId: UUID,
    // Nullable desde a Fase B1: oferta de grupo nasce sem motoboy pre-escolhido.
    val driverId: UUID?,
    val status: DeliveryOfferStatus,
    val feeCents: Long,
    val distanceKm: Double?,
    val offeredAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        fun from(o: DeliveryOffer) = DeliveryOfferResponse(
            id = o.id!!,
            orderId = o.orderId,
            driverId = o.driverId,
            status = o.status,
            feeCents = o.feeCents,
            distanceKm = o.distanceKm,
            offeredAt = o.offeredAt,
            expiresAt = o.expiresAt,
        )
    }
}

data class AssignDriverRequest(
    val driverId: UUID,
)

data class DeliveryStatusUpdateRequest(
    val deliveryStatus: DeliveryStatus,
)

/**
 * Response + STOMP payload for a delivery dispatch.
 *
 * Fase 1B (2026-07-01): campos de endereço, geocode, canal e pagamento adicionados
 * de forma aditiva (todos opcionais ou com default) para suportar a tela /delivery
 * sem quebrar clientes existentes.
 */
data class DeliveryOrderResponse(
    val orderId: UUID,
    val orderNumber: String,
    val driverId: UUID?,
    val deliveryStatus: DeliveryStatus?,
    val totalCents: Long,
    val tableNumber: String?,
    val updatedAt: Instant,
    // --- Fase 1B: campos de entrega ---
    val externalOrigin: String,
    val externalDisplayId: String?,
    val deliveryRecipientName: String?,
    val deliveryPhone: String?,
    val deliveryNeighborhood: String?,
    val deliveryCity: String?,
    val deliveryStreet: String?,
    val deliveryNumber: String?,
    val deliveryComplement: String?,
    val deliveryReference: String?,
    val deliveryLat: Double?,
    val deliveryLng: Double?,
    val deliveryFeeCents: Long,
    /** SalesChannel.name serializado como String para evitar acoplamento de enum cross-módulo. */
    val salesChannel: String,
    /** PaymentStatus.name serializado como String. */
    val paymentStatus: String,
    val createdAt: Instant,
    /**
     * Posicao (1-based) do pedido na rota otimizada do motoboy (issue #4, F2). Null
     * quando o pedido nao faz parte de uma rota confirmada. O app do motoboy usa este
     * campo para exibir a ORDEM das paradas em GET /delivery/orders/my.
     */
    val deliverySequence: Int? = null,
) {
    companion object {
        fun from(o: Order) = DeliveryOrderResponse(
            orderId = o.id!!,
            orderNumber = o.orderNumber,
            driverId = o.driverId,
            deliveryStatus = o.deliveryStatus,
            totalCents = o.totalCents,
            tableNumber = o.tableNumber,
            updatedAt = o.updatedAt,
            externalOrigin = o.externalOrigin,
            externalDisplayId = o.externalDisplayId,
            deliveryRecipientName = o.deliveryRecipientName,
            deliveryPhone = o.deliveryPhone,
            deliveryNeighborhood = o.deliveryNeighborhood,
            deliveryCity = o.deliveryCity,
            deliveryStreet = o.deliveryStreet,
            deliveryNumber = o.deliveryNumber,
            deliveryComplement = o.deliveryComplement,
            deliveryReference = o.deliveryReference,
            deliveryLat = o.deliveryLat,
            deliveryLng = o.deliveryLng,
            deliveryFeeCents = o.deliveryFeeCents,
            salesChannel = o.salesChannel.name,
            paymentStatus = o.paymentStatus.name,
            createdAt = o.createdAt,
            deliverySequence = o.deliverySequence,
        )
    }
}

// ---------------------------------------------------------------------------
// Roteirizacao de multiplas entregas (issue #4)
// ---------------------------------------------------------------------------

/**
 * F1 — pedido de otimizacao (stateless). [orderIds] sao os pedidos de ENTREGA do
 * tenant que sairao juntos com um motoboy. A ordem enviada e irrelevante: o servico
 * a reordena. Bound de tamanho validado no servico (o brute-force do OSRM /trip
 * degrada acima de ~10-12 pontos; teto conservador de 25).
 */
data class RouteOptimizeRequest(
    @field:NotEmpty(message = "Informe ao menos um pedido")
    val orderIds: List<UUID>,
)

/**
 * F2 — confirmacao/atribuicao da rota a um motoboy. [orderIds] JA vem na ORDEM da
 * rota (a sequencia devolvida pelo F1); o servico grava delivery_sequence 1..N nessa
 * ordem e associa os pedidos ao [driverId]. Idempotente: reenviar a mesma rota
 * converge para o mesmo estado.
 */
data class RouteAssignRequest(
    @field:NotNull val driverId: UUID,
    @field:NotEmpty(message = "Informe ao menos um pedido")
    val orderIds: List<UUID>,
)

/**
 * Pedido de ENTREGA aguardando despacho (issue #4): DELIVERY, sem motoboy, com
 * coordenadas, em estado ativo de cozinha. E a fonte do "Passo 1" do planejador de
 * rota — o operador seleciona destes para otimizar+atribuir a um motoboy da frota.
 * So os campos que a tela precisa (endereco/coords sao PII: servidos apenas por este
 * endpoint autenticado + RBAC, do banco do tenant).
 */
data class PendingDeliveryOrderResponse(
    val orderId: UUID,
    val orderNumber: String,
    val deliveryRecipientName: String?,
    val deliveryStreet: String?,
    val deliveryNumber: String?,
    val deliveryNeighborhood: String?,
    val deliveryLat: Double,
    val deliveryLng: Double,
    val totalCents: Long,
) {
    companion object {
        fun from(o: Order) = PendingDeliveryOrderResponse(
            orderId = o.id!!,
            orderNumber = o.orderNumber,
            deliveryRecipientName = o.deliveryRecipientName,
            deliveryStreet = o.deliveryStreet,
            deliveryNumber = o.deliveryNumber,
            deliveryNeighborhood = o.deliveryNeighborhood,
            // Nao-nulos por contrato da query (WHERE lat/lng IS NOT NULL).
            deliveryLat = o.deliveryLat!!,
            deliveryLng = o.deliveryLng!!,
            totalCents = o.totalCents,
        )
    }
}

/** Uma parada da rota (na ordem de visita). position e 1-based. */
data class RouteStopResponse(
    val orderId: UUID,
    val position: Int,
    val orderNumber: String,
    val deliveryLat: Double,
    val deliveryLng: Double,
    val deliveryRecipientName: String?,
    val deliveryNeighborhood: String?,
    val deliveryStreet: String?,
    val deliveryNumber: String?,
)

/**
 * Resposta da otimizacao (F1) e da confirmacao (F2). [optimized] = true quando a
 * ordem veio do OSRM /trip; false quando caiu no fallback deterministico (Haversine
 * a partir do restaurante) por OSRM indisponivel/nao configurado. Totais em metros/
 * segundos; [totalDurationSeconds] e null no fallback (sem estimativa de tempo).
 */
data class RouteOptimizeResponse(
    val stops: List<RouteStopResponse>,
    val totalDistanceMeters: Long,
    val totalDurationSeconds: Long?,
    val optimized: Boolean,
)

// ---------------------------------------------------------------------------
// Fase 6.2 — app do motoboy: perfil, ganhos e vinculo user<->driver
// ---------------------------------------------------------------------------

/** Config de remuneração exposta no perfil (o motoboy vê o PRÓPRIO combinado; leitura). */
data class DriverPayConfigView(
    val dailyRateCents: Long,
    val perDeliveryCents: Long,
    val perKmCents: Long,
)

/** Perfil do motoboy logado (GET /delivery/me). payConfig nulo = gestor ainda não configurou. */
data class DriverMeResponse(
    val id: UUID,
    val name: String,
    val phone: String,
    val licensePlate: String?,
    val active: Boolean,
    val activeShift: Boolean,
    val driverType: String,
    val provisional: Boolean,
    val lastLocationAt: Instant?,
    val payConfig: DriverPayConfigView?,
) {
    companion object {
        fun from(d: DeliveryDriver, c: DriverConfig?) = DriverMeResponse(
            id = d.id!!,
            name = d.name,
            phone = d.phone,
            licensePlate = d.licensePlate,
            active = d.active,
            activeShift = d.activeShift,
            driverType = d.driverType,
            provisional = d.provisional,
            lastLocationAt = d.lastLocationAt,
            payConfig = c?.let {
                DriverPayConfigView(
                    dailyRateCents = it.dailyRateCents,
                    perDeliveryCents = it.perDeliveryCents,
                    perKmCents = it.perKmCents,
                )
            },
        )
    }
}

/**
 * Ganhos do motoboy no período (GET /delivery/earnings/my). Dinheiro em CENTAVOS.
 * deliveryEarningsCents = deliveriesCount x perDeliveryCents (mesma contagem do
 * acerto financeiro). Diária/km são informativos: fecham no acerto do gestor.
 */
data class DriverEarningsResponse(
    val from: LocalDate,
    val to: LocalDate,
    val deliveriesCount: Long,
    val deliveryEarningsCents: Long,
    val perDeliveryCents: Long,
    val dailyRateCents: Long,
    val perKmCents: Long,
    val hasConfig: Boolean,
)

/** Vínculo driver ↔ usuário DRIVER do banco de controle. userId nulo desvincula. */
data class DriverUserLinkRequest(
    val userId: UUID?,
)

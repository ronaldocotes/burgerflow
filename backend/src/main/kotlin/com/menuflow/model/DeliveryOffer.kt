package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Oferta de entrega (Fase 6.1) — vive no banco do TENANT (db-per-tenant), ja escopada
 * pelo restaurante por construcao. O auto-assign cria uma oferta OFFERED para o
 * entregador mais proximo; ele aceita/recusa antes de [expiresAt], senao um job a
 * expira. O indice EXCLUDE (V37) garante no maximo uma OFFERED viva por pedido.
 *
 * fee_cents em CENTAVOS (nunca float). distanceKm e a distancia estimada de rua
 * (linha reta x 1.3) usada para calcular a tarifa e informar o motoboy.
 */
@Entity
@Table(name = "delivery_offers")
class DeliveryOffer(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Column(name = "driver_id", nullable = false)
    val driverId: UUID,

    @Column(name = "status", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    var status: DeliveryOfferStatus = DeliveryOfferStatus.OFFERED,

    @Column(name = "fee_cents", nullable = false)
    val feeCents: Long,

    @Column(name = "distance_km")
    val distanceKm: Double? = null,

    @Column(name = "offered_at", nullable = false, updatable = false)
    val offeredAt: Instant = Instant.now(),

    @Column(name = "responded_at")
    var respondedAt: Instant? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
)

/** Ciclo de vida da oferta. OFFERED -> ACCEPTED | REJECTED | EXPIRED (terminais). */
enum class DeliveryOfferStatus {
    OFFERED,
    ACCEPTED,
    REJECTED,
    EXPIRED,
}

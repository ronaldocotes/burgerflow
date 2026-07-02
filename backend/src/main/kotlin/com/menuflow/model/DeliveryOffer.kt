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
 *
 * Fase B1 (despacho por grupo): a oferta pode nascer SEM motoboy definido (driverId
 * null, groupJid setado) e virar um broadcast; o vencedor emerge em acceptedByDriverId
 * pelo aceite atomico (version = lock otimista). payoutCents separa o repasse ao
 * motoboy da tarifa cobrada do cliente (feeCents). As ofertas legadas (auto-assign)
 * continuam com driverId preenchido e groupJid null.
 */
@Entity
@Table(name = "delivery_offers")
class DeliveryOffer(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    // Nullable desde a V40: oferta de grupo nasce sem motoboy pre-escolhido.
    @Column(name = "driver_id")
    val driverId: UUID? = null,

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

    // --- Fase B1: despacho por grupo de WhatsApp ---
    /** Repasse pago ao MOTOBOY (centavos), separado da tarifa do cliente (feeCents). */
    @Column(name = "payout_cents")
    var payoutCents: Long? = null,

    /** Distancia rodoviaria (metros) usada na precificacao; null se sem geocode. */
    @Column(name = "distance_meters")
    var distanceMeters: Long? = null,

    /** Rotulo do bairro (anonimizado) mostrado no grupo — sem endereco completo (LGPD). */
    @Column(name = "neighborhood_label", length = 60)
    var neighborhoodLabel: String? = null,

    /** JID do grupo de WhatsApp onde a oferta foi/sera publicada. */
    @Column(name = "group_jid", length = 100)
    var groupJid: String? = null,

    /** Id da mensagem do poll no WAHA (correlaciona respostas do grupo). */
    @Column(name = "waha_poll_message_id", length = 100)
    var wahaPollMessageId: String? = null,

    /** Numero da tentativa de despacho (1..dispatchMaxAttempts). */
    @Column(name = "attempt", nullable = false)
    var attempt: Int = 1,

    /**
     * Codigo curto que o motoboy digita no grupo para aceitar (ex.: "ACEITO AB12CD34").
     * Unico entre as ofertas OFFERED (indice parcial na V40). Gerado no createOffer.
     */
    @Column(name = "accept_code", length = 8)
    var acceptCode: String? = null,

    /** Motoboy vencedor do aceite atomico (null enquanto OFFERED). */
    @Column(name = "accepted_by_driver_id")
    var acceptedByDriverId: UUID? = null,

    /** Momento do aceite. */
    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,
)

/** Ciclo de vida da oferta. OFFERED -> ACCEPTED | REJECTED | EXPIRED (terminais). */
enum class DeliveryOfferStatus {
    OFFERED,
    ACCEPTED,
    REJECTED,
    EXPIRED,
}

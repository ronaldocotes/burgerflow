package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** Tipo de evento de marketing: clique no link ou conversao (pedido fechado). */
enum class MarketingEventType { CLICK, CONVERSION }

/**
 * Evento de marketing first-party (Fase 3.6) ligado a um [TrackingLink]. CLICK e
 * registrado quando o cliente abre o link; CONVERSION quando um pedido e finalizado
 * com aquele link (carregando a receita em centavos).
 *
 * Privacidade: [customerIp] e SEMPRE anonimizado antes de persistir (IPv4 com o ultimo
 * octeto zerado, IPv6 reduzido a /64) e [userAgent] e truncado em 500 chars.
 *
 * Usa @ManyToOne para o link de forma que a consulta de resumo (ROAS) possa juntar
 * eventos x links via JPQL. orderId/revenueCents so sao preenchidos em CONVERSION.
 */
@Entity
@Table(name = "marketing_events")
class MarketingEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tracking_link_id", nullable = false)
    val trackingLink: TrackingLink,

    @Column(name = "event_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val eventType: MarketingEventType,

    @Column(name = "order_id")
    val orderId: UUID? = null,

    @Column(name = "revenue_cents")
    val revenueCents: Long? = null,

    @Column(name = "customer_ip", length = 45)
    val customerIp: String? = null,

    @Column(name = "user_agent", length = 500)
    val userAgent: String? = null,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant = Instant.now(),
)

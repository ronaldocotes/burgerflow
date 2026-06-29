package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** Plataforma de destino do evento de conversao. */
enum class ConversionPlatform {
    /** Meta Conversions API (Facebook/Instagram Ads). */
    META,

    /** Google via sGTM (Server-Side Google Tag Manager -> Measurement Protocol GA4). */
    GOOGLE,
}

/** Estado do despacho de conversao. PENDING/FAILED sao retentaveis; SENT/SKIPPED sao finais. */
enum class ConversionStatus {
    /** Criado, ainda nao enviado. */
    PENDING,

    /** Enviado com sucesso (resposta 2xx da plataforma). */
    SENT,

    /** Falhou; sera retentado pelo job ate o limite de tentativas. */
    FAILED,

    /** Esgotou as tentativas sem sucesso; nao sera mais tentado automaticamente. */
    SKIPPED,
}

/**
 * Despacho de um evento de conversao (compra) para uma plataforma de anuncios.
 * Vive no banco do TENANT (db-per-tenant). Um pedido pago gera ate um despacho por
 * plataforma; o indice unico (order_id, platform) garante a idempotencia — o mesmo
 * pedido nunca conta duas vezes na mesma plataforma, mesmo sob reenvio/retry.
 *
 * Nunca guarda PII em claro: o telefone do cliente e hasheado (SHA-256) antes de
 * sair do processo; aqui guardamos apenas o hash do payload (auditoria) e a resposta.
 */
@Entity
@Table(name = "conversion_dispatches")
data class ConversionDispatch(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    val platform: ConversionPlatform,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ConversionStatus = ConversionStatus.PENDING,

    /** Identidade de deduplicacao do evento (ex.: "order-{orderId}"). */
    @Column(name = "event_id", length = 100)
    var eventId: String? = null,

    /** SHA-256 do JSON enviado — auditoria e deteccao de reenvio do mesmo payload. */
    @Column(name = "payload_hash", length = 64)
    var payloadHash: String? = null,

    @Column(name = "response_code")
    var responseCode: Int? = null,

    @Column(name = "response_body", columnDefinition = "text")
    var responseBody: String? = null,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_attempt_at")
    var lastAttemptAt: Instant? = null,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Intencao de pagamento PIX (Asaas). Vive no banco do TENANT (db-per-tenant), entao
 * cada cobranca ja pertence fisicamente a um unico restaurante — nao ha coluna de
 * escopo. Dinheiro em CENTAVOS (amountCents). O snapshot do QR (imagem base64 +
 * copia-e-cola) e guardado para reexibir sem nova chamada ao Asaas.
 */
@Entity
@Table(name = "payment_intents")
data class PaymentIntent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Pedido (orders.id) que esta cobranca quita. */
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    /** Id da cobranca no Asaas (preenchido apos a criacao remota). UNIQUE. */
    @Column(name = "asaas_payment_id")
    var asaasPaymentId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentIntentStatus = PaymentIntentStatus.PENDING,

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long,

    /** Imagem do QR Code PIX em base64 (encodedImage do Asaas). */
    @Column(name = "pix_qr_image", columnDefinition = "text")
    var pixQrImage: String? = null,

    /** Payload copia-e-cola (BR Code). */
    @Column(name = "pix_copy_paste", columnDefinition = "text")
    var pixCopyPaste: String? = null,

    @Column(name = "paid_at")
    var paidAt: Instant? = null,

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

enum class PaymentIntentStatus {
    PENDING,
    PAID,
    FAILED,
    EXPIRED,
}

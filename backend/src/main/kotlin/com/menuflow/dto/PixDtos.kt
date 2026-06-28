package com.menuflow.dto

import com.menuflow.client.AsaasPaymentResponse
import com.menuflow.model.PaymentIntent
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/** Pedido para gerar (ou recuperar) o QR PIX de um pedido. */
data class CreatePixQrRequest(
    @field:NotNull
    val orderId: UUID,
)

/**
 * Estado de uma cobranca PIX exposto ao PDV/cliente. NAO expoe asaasPaymentId nem
 * o customer do Asaas (detalhe de infraestrutura); so o que a tela precisa para
 * mostrar o QR e fazer polling do status.
 */
data class PaymentIntentResponse(
    val id: UUID,
    val orderId: UUID,
    val status: String,
    val pixQrImage: String?,
    val pixCopyPaste: String?,
    val amountCents: Long,
    val expiresAt: Instant?,
) {
    companion object {
        fun from(pi: PaymentIntent) = PaymentIntentResponse(
            id = pi.id!!,
            orderId = pi.orderId,
            status = pi.status.name,
            pixQrImage = pi.pixQrImage,
            pixCopyPaste = pi.pixCopyPaste,
            amountCents = pi.amountCents,
            expiresAt = pi.expiresAt,
        )
    }
}

/**
 * Corpo do webhook do Asaas. Sem HMAC (o Asaas nao assina): a defesa pratica e que
 * `payment.id` (asaasPaymentId) so existe no banco do tenant correto e e um id
 * NAO adivinhavel gerado pelo Asaas. Ainda assim, recomenda-se IP allowlist /
 * token por tenant em producao (ver nota no controller).
 */
data class AsaasWebhookBody(
    val id: String,
    val event: String,
    val payment: AsaasPaymentResponse,
)

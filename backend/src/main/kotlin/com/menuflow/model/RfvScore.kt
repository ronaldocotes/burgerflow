package com.menuflow.model

import java.util.UUID

/**
 * Score RFV (Recencia, Frequencia, Valor) de um cliente (Fase 3.4). Nao e entidade
 * JPA — e calculado em memoria pelo RfvService a partir dos pedidos do tenant.
 * Dinheiro em centavos (Long).
 */
data class RfvScore(
    val customerId: UUID,
    val customerName: String?,
    /** Telefone do cliente — usado na exportacao CSV e nas campanhas WhatsApp. */
    val phoneNumber: String?,
    /** Dias desde o ultimo pedido (toda a historia). */
    val recencyDays: Int,
    /** Numero de pedidos nos ultimos 90 dias. */
    val frequency: Int,
    /** Ticket medio em centavos nos ultimos 90 dias. */
    val monetaryValue: Long,
    val segment: RfvSegment,
)

/** Segmento RFV do cliente. */
enum class RfvSegment {
    /** Recencia < 14 dias e frequencia >= 3. */
    LOYAL,

    /** Tem historia, nem sumiu nem e claramente fiel (catch-all de reengajamento). */
    AT_RISK,

    /** Recencia > 45 dias (sumido). */
    INACTIVE,

    /** Apenas 1 pedido na vida (cliente novo). */
    NEW,
}

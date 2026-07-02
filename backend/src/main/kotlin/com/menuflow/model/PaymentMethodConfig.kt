package com.menuflow.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Configuracao de uma forma de pagamento (issue #8). Vive no banco do TENANT
 * (db-per-tenant), 1 linha por forma. Toggle liga/desliga + taxa (%) + opcao de
 * repassar a taxa ao cliente.
 *
 * [method] e a chave natural (nome do enum [PaymentMethod] como PIX/CREDIT_CARD/...
 * mais extras como MEAL_VOUCHER que nao existem no enum de pedido). Este catalogo
 * e a fonte de verdade da config de checkout; o DRE (V22) segue com sua aliquota
 * unica de cartao ate uma futura unificacao.
 */
@Entity
@Table(name = "payment_method_configs")
data class PaymentMethodConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Chave da forma (ex.: PIX, CREDIT_CARD, MEAL_VOUCHER). Unica por tenant. */
    @Column(name = "method", nullable = false, length = 30)
    var method: String,

    /** Rotulo exibido no checkout. */
    @Column(name = "label", nullable = false, length = 60)
    var label: String,

    /** Forma habilitada no checkout. */
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    /** Taxa (%) da forma (0..100). */
    @Column(name = "fee_pct", nullable = false, precision = 5, scale = 2)
    var feePct: BigDecimal = BigDecimal.ZERO,

    /** Se true, a taxa e somada ao total cobrado do cliente (repasse). */
    @Column(name = "pass_fee_to_customer", nullable = false)
    var passFeeToCustomer: Boolean = false,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

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

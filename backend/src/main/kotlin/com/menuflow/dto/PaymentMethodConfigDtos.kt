package com.menuflow.dto

import com.menuflow.model.PaymentMethodConfig
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

data class PaymentMethodConfigResponse(
    val id: UUID?,
    val method: String,
    val label: String,
    val enabled: Boolean,
    val feePct: BigDecimal,
    val passFeeToCustomer: Boolean,
    val sortOrder: Int,
) {
    companion object {
        fun from(c: PaymentMethodConfig) = PaymentMethodConfigResponse(
            id = c.id,
            method = c.method,
            label = c.label,
            enabled = c.enabled,
            feePct = c.feePct,
            passFeeToCustomer = c.passFeeToCustomer,
            sortOrder = c.sortOrder,
        )
    }
}

/**
 * Upsert de uma forma de pagamento. A chave [method] identifica a linha (cria se
 * nova, atualiza se existe). Restrito a MAIUSCULAS/underscore para casar o padrao
 * do enum (PIX, CREDIT_CARD, MEAL_VOUCHER).
 */
data class PaymentMethodConfigUpsertRequest(
    @field:NotBlank
    @field:Size(max = 30)
    @field:Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "method deve ser MAIUSCULO com underscore")
    val method: String,
    @field:NotBlank @field:Size(max = 60)
    val label: String,
    val enabled: Boolean = true,
    @field:DecimalMin("0.0") @field:DecimalMax("100.0")
    val feePct: BigDecimal = BigDecimal.ZERO,
    val passFeeToCustomer: Boolean = false,
    @field:Min(0)
    val sortOrder: Int = 0,
)

package com.menuflow.dto

import com.menuflow.model.DeliveryZone
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Um anel de entrega no PUT /delivery/zones. O max_radius_km e validado no service
 * (bounds de Double + ordem crescente do conjunto); os campos inteiros/monetarios
 * ganham bound defensivo aqui (sinal + teto sao) — dinheiro em centavos, nunca float.
 * A ordem no array define o display_order (menor raio primeiro).
 */
data class DeliveryZoneUpsert(
    @field:Size(max = 100) val name: String? = null,
    /** Raio externo do anel (km, linha reta). Bounds validados no service. */
    val maxRadiusKm: Double,
    @field:PositiveOrZero @field:Max(DeliveryZoneLimits.MAX_FEE_CENTS)
    val feeCents: Long,
    @field:PositiveOrZero @field:Max(DeliveryZoneLimits.MAX_ETA_MINUTES.toLong())
    val etaMinMinutes: Int,
    @field:PositiveOrZero @field:Max(DeliveryZoneLimits.MAX_ETA_MINUTES.toLong())
    val etaMaxMinutes: Int,
    val isFree: Boolean = false,
)

/**
 * Substitui o conjunto INTEIRO de aneis de uma vez (idempotente por natureza): o PUT
 * apaga as zonas atuais e grava as enviadas. Inclui o limiar global de frete gratis
 * por valor de pedido (freeDeliveryMinOrderCents), que a tela de area de cobertura e
 * dona de ajustar (persistido em tenant_config).
 */
data class DeliveryZonesRequest(
    @field:Valid val zones: List<DeliveryZoneUpsert> = emptyList(),
    /** Frete gratis quando subtotal >= este valor (centavos). Null = desabilitado. */
    @field:PositiveOrZero @field:Max(DeliveryZoneLimits.MAX_FREE_MIN_ORDER_CENTS)
    val freeDeliveryMinOrderCents: Long? = null,
)

data class DeliveryZoneView(
    val id: UUID?,
    val name: String?,
    val maxRadiusKm: Double,
    val feeCents: Long,
    val etaMinMinutes: Int,
    val etaMaxMinutes: Int,
    val isFree: Boolean,
    val displayOrder: Int,
    val active: Boolean,
) {
    companion object {
        fun from(z: DeliveryZone) = DeliveryZoneView(
            id = z.id,
            name = z.name,
            maxRadiusKm = z.maxRadiusKm,
            feeCents = z.feeCents,
            etaMinMinutes = z.etaMinMinutes,
            etaMaxMinutes = z.etaMaxMinutes,
            isFree = z.isFree,
            displayOrder = z.displayOrder,
            active = z.active,
        )
    }
}

data class DeliveryZonesResponse(
    val zones: List<DeliveryZoneView>,
    val freeDeliveryMinOrderCents: Long?,
)

/** Limites sanos (server-side) para as zonas de entrega. Compartilhados DTO+service. */
object DeliveryZoneLimits {
    /** Teto do frete por zona: R$ 1.000,00. */
    const val MAX_FEE_CENTS = 100_000L
    /** Teto de ETA (minutos) por zona. */
    const val MAX_ETA_MINUTES = 600
    /** Raio maximo de um anel (km). */
    const val MAX_RADIUS_KM = 100.0
    /** Teto do limiar de frete gratis por valor de pedido: R$ 1.000.000,00. */
    const val MAX_FREE_MIN_ORDER_CENTS = 100_000_000L
}

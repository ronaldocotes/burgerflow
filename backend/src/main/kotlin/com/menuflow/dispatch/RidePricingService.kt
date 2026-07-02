package com.menuflow.dispatch

import com.menuflow.model.TenantConfig
import org.springframework.stereotype.Service

/**
 * Precificacao da corrida de entrega. Separa DOIS valores desde ja (decisao D-4):
 *  - feeCents:    o que o CLIENTE paga pela entrega.
 *  - payoutCents: o que o restaurante paga ao MOTOBOY pela corrida.
 *
 * Ambos com a mesma forma, mas parametros proprios:
 *   valor = max(min, base + porKm * max(0, km - raioGratis))
 *
 * Dinheiro SEMPRE em centavos (Long); a parte por-km e calculada em double (km) e
 * arredondada UMA vez para centavos (HALF-UP via Math.round) na borda -- nunca
 * guardamos float de dinheiro. Os parametros de payout sao nullable no tenant_config:
 * quando null, o payout ESPELHA a taxa (base/porKm/min da taxa), garantindo um
 * default seguro para quem ainda nao configurou o repasse separadamente.
 */
@Service
class RidePricingService {

    /** Tarifa cobrada do cliente (centavos). */
    fun feeCents(config: TenantConfig, distanceMeters: Long): Long {
        val billableKm = billableKm(config, distanceMeters)
        val variable = Math.round(config.deliveryFeePerKmCents * billableKm)
        val raw = config.deliveryBaseFeeCents + variable
        return maxOf(config.deliveryMinFeeCents, raw)
    }

    /** Repasse pago ao motoboy (centavos). Null nos campos de payout => espelha a taxa. */
    fun payoutCents(config: TenantConfig, distanceMeters: Long): Long {
        val base = config.deliveryBasePayoutCents ?: config.deliveryBaseFeeCents
        val perKm = config.deliveryPerKmPayoutCents ?: config.deliveryFeePerKmCents
        val min = config.deliveryMinPayoutCents ?: config.deliveryMinFeeCents
        val billableKm = billableKm(config, distanceMeters)
        val variable = Math.round(perKm * billableKm)
        return maxOf(min, base + variable)
    }

    /** Km cobravel = km total menos o raio gratis (nunca negativo). */
    private fun billableKm(config: TenantConfig, distanceMeters: Long): Double {
        val km = distanceMeters / 1000.0
        val freeKm = config.deliveryFreeRadiusMeters / 1000.0
        return maxOf(0.0, km - freeKm)
    }
}

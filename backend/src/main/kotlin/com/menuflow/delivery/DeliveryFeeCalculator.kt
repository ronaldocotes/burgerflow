package com.menuflow.delivery

import com.menuflow.model.TenantConfig
import org.springframework.stereotype.Component

/**
 * Calcula a tarifa de entrega (centavos) a partir da distancia e da config do tenant:
 * base + (km * por_km). Aritmetica inteira em centavos — nunca float para dinheiro.
 */
@Component
class DeliveryFeeCalculator {
    fun calculate(distanceKm: Double, config: TenantConfig): Long {
        val variable = (distanceKm * config.deliveryFeePerKmCents).toLong()
        return config.deliveryBaseFeeCents + variable
    }
}

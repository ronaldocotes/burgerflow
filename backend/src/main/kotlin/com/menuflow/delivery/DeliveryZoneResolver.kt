package com.menuflow.delivery

import com.menuflow.model.DeliveryZone
import com.menuflow.repository.tenant.DeliveryZoneRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolucao do frete/ETA por ZONA (issue #2, decisao D-1: raio em LINHA RETA/Haversine).
 *
 * Dada a origem (restaurante) e o destino (ponto de entrega), calcula a distancia
 * Haversine e escolhe a PRIMEIRA zona ativa (menor max_radius_km) cujo raio cobre a
 * distancia. Fora de todos os aneis => null ("fora da area de entrega"), que o
 * OrderService traduz em bloqueio do pedido (nunca aceita frete arbitrario do cliente).
 *
 * Frete gratis: zona com is_free OU (limiar global) subtotal >= freeMinOrderCents.
 * Dinheiro em centavos (Long); a distancia so escolhe a zona, nao entra no valor.
 */
@Component
class DeliveryZoneResolver(
    private val zoneRepository: DeliveryZoneRepository,
) {

    /** Resultado da resolucao de zona para um ponto de entrega. */
    data class ZoneResolution(
        val feeCents: Long,
        val etaMinMinutes: Int,
        val etaMaxMinutes: Int,
        val isFree: Boolean,
        val zoneId: UUID?,
        val zoneName: String?,
        val distanceKm: Double,
    )

    /** Zonas ativas do tenant corrente (ordenadas pelo raio). Vazio = modo flat legado. */
    fun activeZones(): List<DeliveryZone> = zoneRepository.findByActiveTrueOrderByMaxRadiusKmAsc()

    /**
     * Resolve a zona do ponto (origem -> destino). Retorna null se a distancia excede
     * todas as zonas (fora de area). [zones] deve vir ordenado por raio crescente
     * (contrato de [activeZones]); a busca pega a primeira que cobre.
     */
    fun resolve(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        subtotalCents: Long,
        freeMinOrderCents: Long?,
        zones: List<DeliveryZone>,
    ): ZoneResolution? {
        val distanceKm = HaversineUtil.distanceKm(originLat, originLng, destLat, destLng)
        // Menor anel que cobre a distancia. Como a lista vem ordenada por raio asc,
        // firstOrNull ja devolve o de menor raio.
        val zone = zones.firstOrNull { it.maxRadiusKm >= distanceKm } ?: return null
        val freeByThreshold = freeMinOrderCents != null && subtotalCents >= freeMinOrderCents
        val free = zone.isFree || freeByThreshold
        val fee = if (free) 0L else zone.feeCents
        return ZoneResolution(
            feeCents = fee,
            etaMinMinutes = zone.etaMinMinutes,
            etaMaxMinutes = zone.etaMaxMinutes,
            isFree = free,
            zoneId = zone.id,
            zoneName = zone.name,
            distanceKm = distanceKm,
        )
    }
}

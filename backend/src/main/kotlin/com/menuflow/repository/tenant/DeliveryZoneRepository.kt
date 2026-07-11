package com.menuflow.repository.tenant

import com.menuflow.model.DeliveryZone
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DeliveryZoneRepository : JpaRepository<DeliveryZone, UUID> {

    /**
     * Zonas ATIVAS ordenadas pelo raio crescente. O resolver pega a PRIMEIRA cujo
     * max_radius_km >= distancia (menor anel que cobre o ponto). Vazio = tenant sem
     * zonas -> cai no calculo flat legado (DeliveryFeeCalculator/RidePricing).
     */
    fun findByActiveTrueOrderByMaxRadiusKmAsc(): List<DeliveryZone>

    /** Listagem para a tela de config (todas as zonas na ordem de exibicao). */
    fun findAllByOrderByDisplayOrderAsc(): List<DeliveryZone>
}

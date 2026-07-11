package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Anel de cobertura de entrega por RAIO (issue #2). Vive no banco do TENANT
 * (db-per-tenant), entao nao tem coluna de escopo. Espelha
 * db/tenant/migration/V61__delivery_zones.sql.
 *
 * Decisao do dono D-1: o anel e resolvido por distancia em LINHA RETA (Haversine)
 * do restaurante (TenantConfig.restaurantLat/Lng) ao ponto de entrega. Cada anel
 * cobre do raio anterior ate [maxRadiusKm]; o resolver escolhe a zona de MENOR
 * [maxRadiusKm] cujo raio >= distancia. Dinheiro em centavos (Long), nunca float.
 */
@Entity
@Table(name = "delivery_zone")
class DeliveryZone(
    /** Rotulo opcional do anel (ex.: "Centro", "Ate 2km"). */
    @Column(length = 100)
    var name: String? = null,

    /** Raio externo do anel, em km (linha reta a partir do restaurante). */
    @Column(name = "max_radius_km", nullable = false)
    var maxRadiusKm: Double,

    /** Frete cobrado do cliente nesta zona (centavos). Ignorado quando [isFree]. */
    @Column(name = "fee_cents", nullable = false)
    var feeCents: Long,

    /** Promessa de prazo minimo (minutos) desta zona. */
    @Column(name = "eta_min_minutes", nullable = false)
    var etaMinMinutes: Int,

    /** Promessa de prazo maximo (minutos) desta zona. */
    @Column(name = "eta_max_minutes", nullable = false)
    var etaMaxMinutes: Int,

    /** Anel de frete gratis: quando true, o frete e 0 (fee ignorado). */
    @Column(name = "is_free", nullable = false)
    var isFree: Boolean = false,

    /** Ordem dos aneis (do menor raio ao maior); definida pelo indice no PUT. */
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(nullable = false)
    var active: Boolean = true,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

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

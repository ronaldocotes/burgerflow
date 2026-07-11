package com.menuflow.service

import com.menuflow.dto.DeliveryZoneLimits
import com.menuflow.dto.DeliveryZoneView
import com.menuflow.dto.DeliveryZonesRequest
import com.menuflow.dto.DeliveryZonesResponse
import com.menuflow.model.DeliveryZone
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.DeliveryZoneRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * CRUD das zonas de entrega por raio (issue #2). Vive no banco do TENANT
 * (db-per-tenant): sem escopo cross-tenant a checar — a conexao ja aterrissa no
 * banco certo pela TenantContext do token. RBAC (ADMIN/MANAGER) fica no controller.
 *
 * O PUT substitui o CONJUNTO inteiro de aneis de uma vez (mais simples que CRUD
 * item-a-item e naturalmente idempotente: reenviar o mesmo conjunto converge ao mesmo
 * estado). Validacoes server-side de dinheiro/raio/ETA (fase de DINHEIRO) sao
 * obrigatorias aqui, independentes do bean validation do DTO.
 */
@Service
class DeliveryZoneService(
    private val zoneRepository: DeliveryZoneRepository,
    private val tenantConfigRepository: TenantConfigRepository,
) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(): DeliveryZonesResponse {
        val zones = zoneRepository.findAllByOrderByDisplayOrderAsc().map(DeliveryZoneView::from)
        val threshold = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()?.freeDeliveryMinOrderCents
        return DeliveryZonesResponse(zones, threshold)
    }

    /**
     * Substitui o conjunto de aneis + o limiar de frete gratis por valor. Valida:
     * raio > 0 e <= teto, sem raios repetidos/sobrepostos e em ordem ESTRITAMENTE
     * crescente; fee >= 0 e <= teto; eta_min <= eta_max; limiar >= 0. O display_order
     * e o indice no array (menor raio primeiro).
     */
    @Transactional("tenantTransactionManager")
    fun replace(req: DeliveryZonesRequest): DeliveryZonesResponse {
        validate(req)

        // Substituicao do conjunto inteiro (idempotente). deleteAllInBatch limpa as
        // atuais numa unica DML antes dos inserts.
        zoneRepository.deleteAllInBatch()
        val saved = req.zones.mapIndexed { i, z ->
            zoneRepository.save(
                DeliveryZone(
                    name = z.name?.trim()?.ifBlank { null },
                    maxRadiusKm = z.maxRadiusKm,
                    feeCents = z.feeCents,
                    etaMinMinutes = z.etaMinMinutes,
                    etaMaxMinutes = z.etaMaxMinutes,
                    isFree = z.isFree,
                    displayOrder = i,
                    active = true,
                ),
            )
        }.map(DeliveryZoneView::from)

        // Limiar global de frete gratis por valor -> tenant_config (getOrCreate, como
        // TenantConfigService). Enviado null limpa (desabilita).
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()
        config.freeDeliveryMinOrderCents = req.freeDeliveryMinOrderCents
        tenantConfigRepository.save(config)

        return DeliveryZonesResponse(saved, req.freeDeliveryMinOrderCents)
    }

    private fun validate(req: DeliveryZonesRequest) {
        req.freeDeliveryMinOrderCents?.let {
            require(it >= 0) { "freeDeliveryMinOrderCents não pode ser negativo" }
            require(it <= DeliveryZoneLimits.MAX_FREE_MIN_ORDER_CENTS) {
                "freeDeliveryMinOrderCents acima do teto permitido"
            }
        }
        var previousRadius = 0.0
        req.zones.forEachIndexed { i, z ->
            require(z.maxRadiusKm > 0.0) { "raio da zona ${i + 1} deve ser maior que zero" }
            require(z.maxRadiusKm <= DeliveryZoneLimits.MAX_RADIUS_KM) {
                "raio da zona ${i + 1} acima do teto (${DeliveryZoneLimits.MAX_RADIUS_KM} km)"
            }
            // Ordem estritamente crescente: cada anel deve ter raio MAIOR que o anterior
            // (evita sobreposicao/ambiguidade de qual zona cobra o ponto).
            require(z.maxRadiusKm > previousRadius) {
                "os raios das zonas devem ser estritamente crescentes (zona ${i + 1} não é maior que a anterior)"
            }
            previousRadius = z.maxRadiusKm
            require(z.feeCents >= 0) { "fee da zona ${i + 1} não pode ser negativo" }
            require(z.feeCents <= DeliveryZoneLimits.MAX_FEE_CENTS) { "fee da zona ${i + 1} acima do teto" }
            require(z.etaMinMinutes >= 0 && z.etaMaxMinutes >= 0) { "ETA da zona ${i + 1} não pode ser negativa" }
            require(z.etaMinMinutes <= z.etaMaxMinutes) {
                "ETA mínima não pode ser maior que a máxima na zona ${i + 1}"
            }
        }
    }
}

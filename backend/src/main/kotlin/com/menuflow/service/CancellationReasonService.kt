package com.menuflow.service

import com.menuflow.dto.CancellationReasonRequest
import com.menuflow.dto.CancellationReasonResponse
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.CancellationReason
import com.menuflow.repository.tenant.CancellationReasonRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * CRUD dos motivos de cancelamento (issue #10). A linha vive no banco do proprio
 * tenant (rota pela TenantContext), sem escopo cross-tenant a checar aqui.
 */
@Service
class CancellationReasonService(
    private val repository: CancellationReasonRepository,
) {

    /** [activeOnly]=true -> so os ativos (seletor de cancelamento); false -> todos (admin). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(activeOnly: Boolean): List<CancellationReasonResponse> {
        val rows = if (activeOnly) {
            repository.findAllByActiveTrueOrderBySortOrderAscDescriptionAsc()
        } else {
            repository.findAllByOrderBySortOrderAscDescriptionAsc()
        }
        return rows.map(CancellationReasonResponse::from)
    }

    @Transactional("tenantTransactionManager")
    fun create(req: CancellationReasonRequest): CancellationReasonResponse {
        val entity = CancellationReason(
            description = req.description.trim(),
            active = req.active,
            sortOrder = req.sortOrder,
        )
        return CancellationReasonResponse.from(repository.save(entity))
    }

    @Transactional("tenantTransactionManager")
    fun update(id: UUID, req: CancellationReasonRequest): CancellationReasonResponse {
        val entity = repository.findById(id)
            .orElseThrow { ResourceNotFoundException("Motivo de cancelamento nao encontrado: $id") }
        entity.description = req.description.trim()
        entity.active = req.active
        entity.sortOrder = req.sortOrder
        return CancellationReasonResponse.from(repository.save(entity))
    }

    /**
     * Desativa (soft-delete) o motivo em vez de apagar: pedidos historicos podem
     * ter referenciado o id, e queremos preservar o relatorio de motivos.
     */
    @Transactional("tenantTransactionManager")
    fun deactivate(id: UUID) {
        val entity = repository.findById(id)
            .orElseThrow { ResourceNotFoundException("Motivo de cancelamento nao encontrado: $id") }
        entity.active = false
        repository.save(entity)
    }
}

package com.menuflow.service

import com.menuflow.dto.PaymentMethodConfigResponse
import com.menuflow.dto.PaymentMethodConfigUpsertRequest
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.PaymentMethodConfig
import com.menuflow.repository.tenant.PaymentMethodConfigRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * CRUD das formas de pagamento configuraveis (issue #8). A linha vive no banco do
 * proprio tenant (rota pela TenantContext do token assinado), entao nao ha escopo
 * cross-tenant a checar aqui.
 */
@Service
class PaymentMethodConfigService(
    private val repository: PaymentMethodConfigRepository,
) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(): List<PaymentMethodConfigResponse> =
        repository.findAllByOrderBySortOrderAscLabelAsc().map(PaymentMethodConfigResponse::from)

    /** Upsert pela chave natural [method]: cria se nova, atualiza se ja existe. */
    @Transactional("tenantTransactionManager")
    fun upsert(req: PaymentMethodConfigUpsertRequest): PaymentMethodConfigResponse {
        val existing = repository.findByMethod(req.method)
        val entity = existing?.apply {
            label = req.label.trim()
            enabled = req.enabled
            feePct = req.feePct
            passFeeToCustomer = req.passFeeToCustomer
            sortOrder = req.sortOrder
        } ?: PaymentMethodConfig(
            method = req.method,
            label = req.label.trim(),
            enabled = req.enabled,
            feePct = req.feePct,
            passFeeToCustomer = req.passFeeToCustomer,
            sortOrder = req.sortOrder,
        )
        return PaymentMethodConfigResponse.from(repository.save(entity))
    }

    @Transactional("tenantTransactionManager")
    fun delete(id: UUID) {
        val entity = repository.findById(id)
            .orElseThrow { ResourceNotFoundException("Forma de pagamento nao encontrada: $id") }
        repository.delete(entity)
    }
}

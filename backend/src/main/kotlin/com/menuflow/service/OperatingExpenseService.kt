package com.menuflow.service

import com.menuflow.dto.OperatingExpenseRequest
import com.menuflow.dto.OperatingExpenseResponse
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.OperatingExpense
import com.menuflow.repository.tenant.OperatingExpenseRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * CRUD das despesas operacionais (Fase 3.1). Tudo no banco do TENANT (escopo pelo
 * datasource roteado). Dinheiro SEMPRE em centavos. Hard-delete: despesa é um
 * lançamento manual e sem dependências (não referenciada por pedido).
 */
@Service
class OperatingExpenseService(
    private val repository: OperatingExpenseRepository,
    private val auditLogService: AuditLogService,
) {

    /**
     * Lista paginada das despesas de um período [start, end] (inclusivo), mais
     * recentes primeiro. Default amplo (ano corrente) quando o período é omitido.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(start: LocalDate?, end: LocalDate?, pageable: Pageable): Page<OperatingExpenseResponse> {
        val from = start ?: LocalDate.now().withDayOfYear(1)
        val to = end ?: LocalDate.now().withMonth(12).withDayOfMonth(31)
        return repository.findByExpenseDateBetweenOrderByExpenseDateDesc(from, to, pageable)
            .map { OperatingExpenseResponse.from(it) }
    }

    @Transactional("tenantTransactionManager")
    fun create(req: OperatingExpenseRequest, actorId: UUID?): OperatingExpenseResponse {
        val saved = repository.save(
            OperatingExpense(
                description = req.description.trim(),
                amountCents = req.amountCents,
                category = req.category,
                expenseDate = req.expenseDate,
            ),
        )
        auditLogService.log(
            action = "operating_expense.create",
            entity = "operating_expense",
            entityId = saved.id,
            after = mapOf(
                "description" to saved.description,
                "amountCents" to saved.amountCents,
                "category" to saved.category.name,
                "expenseDate" to saved.expenseDate.toString(),
            ),
            actorUserId = actorId,
        )
        return OperatingExpenseResponse.from(saved)
    }

    @Transactional("tenantTransactionManager")
    fun update(id: UUID, req: OperatingExpenseRequest, actorId: UUID?): OperatingExpenseResponse {
        val expense = repository.findById(id)
            .orElseThrow { ResourceNotFoundException("Despesa não encontrada") }
        expense.description = req.description.trim()
        expense.amountCents = req.amountCents
        expense.category = req.category
        expense.expenseDate = req.expenseDate
        val saved = repository.save(expense)
        auditLogService.log(
            action = "operating_expense.update",
            entity = "operating_expense",
            entityId = saved.id,
            after = mapOf(
                "description" to saved.description,
                "amountCents" to saved.amountCents,
                "category" to saved.category.name,
                "expenseDate" to saved.expenseDate.toString(),
            ),
            actorUserId = actorId,
        )
        return OperatingExpenseResponse.from(saved)
    }

    @Transactional("tenantTransactionManager")
    fun delete(id: UUID, actorId: UUID?) {
        if (!repository.existsById(id)) {
            throw ResourceNotFoundException("Despesa não encontrada")
        }
        repository.deleteById(id)
        auditLogService.log(
            action = "operating_expense.delete",
            entity = "operating_expense",
            entityId = id,
            actorUserId = actorId,
        )
    }
}

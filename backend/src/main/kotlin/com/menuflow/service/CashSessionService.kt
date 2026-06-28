package com.menuflow.service

import com.menuflow.dto.CashEntryResponse
import com.menuflow.dto.CashSessionResponse
import com.menuflow.dto.CloseSessionRequest
import com.menuflow.dto.EntryRequest
import com.menuflow.dto.OpenSessionRequest
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.CashEntryType
import com.menuflow.model.CashSession
import com.menuflow.model.CashSessionEntry
import com.menuflow.model.CashSessionStatus
import com.menuflow.repository.tenant.CashSessionEntryRepository
import com.menuflow.repository.tenant.CashSessionRepository
import com.menuflow.repository.tenant.OrderRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Turno de caixa (CashSession). Tudo no banco do TENANT (escopo garantido pelo
 * datasource roteado). Dinheiro SEMPRE em centavos.
 *
 * Esperado na gaveta = abertura + vendas em dinheiro do turno + reforços - sangrias.
 * Vendas em dinheiro = pedidos com cashSessionId deste turno, paymentMethod=CASH e
 * paymentStatus=PAID (o pedido é carimbado com o turno no momento da venda em
 * OrderService.create).
 *
 * Invariante: no máximo um turno OPEN por restaurante — garantido por
 * existsByStatus + índice parcial uq_cash_sessions_single_open (a corrida de dois
 * opens cai em DataIntegrityViolationException, convertido para 409).
 */
@Service
class CashSessionService(
    private val cashSessionRepository: CashSessionRepository,
    private val entryRepository: CashSessionEntryRepository,
    private val orderRepository: OrderRepository,
    private val auditLogService: AuditLogService,
) {

    /** Turno aberto atual, ou null se não houver (controller -> 204). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun current(): CashSessionResponse? =
        cashSessionRepository.findFirstByStatus(CashSessionStatus.OPEN)?.let { toResponse(it) }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(pageable: Pageable): Page<CashSessionResponse> =
        cashSessionRepository.findAll(pageable).map { toResponse(it) }

    @Transactional("tenantTransactionManager")
    fun open(actorId: UUID, req: OpenSessionRequest): CashSessionResponse {
        // Checagem prévia (mensagem amigável) + índice parcial (corrida real).
        if (cashSessionRepository.existsByStatus(CashSessionStatus.OPEN)) {
            throw ConflictException("Já existe um caixa aberto")
        }
        val saved = try {
            cashSessionRepository.save(
                CashSession(
                    status = CashSessionStatus.OPEN,
                    openedByUserId = actorId,
                    openingAmountCents = req.openingAmountCents,
                    notes = req.notes,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // Dois opens concorrentes: o índice parcial barra o segundo.
            throw ConflictException("Já existe um caixa aberto")
        }
        auditLogService.log(
            action = "cash_session.open",
            entity = "cash_session",
            entityId = saved.id,
            after = mapOf("openingAmountCents" to req.openingAmountCents),
            actorUserId = actorId,
        )
        return toResponse(saved)
    }

    @Transactional("tenantTransactionManager")
    fun addEntry(sessionId: UUID, actorId: UUID, req: EntryRequest): CashSessionResponse {
        val session = loadOpen(sessionId)
        entryRepository.save(
            CashSessionEntry(
                sessionId = session.id!!,
                type = req.type,
                amountCents = req.amountCents,
                reason = req.reason,
                createdByUserId = actorId,
            ),
        )
        // Sangria (WITHDRAWAL) ou reforço (DEPOSIT) — movimento manual sensível do caixa.
        val action = if (req.type == CashEntryType.WITHDRAWAL) "cash_session.withdrawal" else "cash_session.deposit"
        auditLogService.log(
            action = action,
            entity = "cash_session",
            entityId = session.id,
            after = mapOf("amountCents" to req.amountCents, "reason" to req.reason),
            actorUserId = actorId,
        )
        return toResponse(session)
    }

    @Transactional("tenantTransactionManager")
    fun close(sessionId: UUID, actorId: UUID, req: CloseSessionRequest): CashSessionResponse {
        val session = loadOpen(sessionId)
        val breakdown = breakdown(session)
        session.closingExpectedCents = breakdown.expected
        session.closingCountedCents = req.countedAmountCents
        session.closedByUserId = actorId
        session.closedAt = Instant.now()
        session.status = CashSessionStatus.CLOSED
        req.notes?.let { session.notes = it }
        val saved = cashSessionRepository.save(session)
        auditLogService.log(
            action = "cash_session.close",
            entity = "cash_session",
            entityId = saved.id,
            after = mapOf(
                "countedCents" to req.countedAmountCents,
                "expectedCents" to breakdown.expected,
                "differenceCents" to (req.countedAmountCents - breakdown.expected),
            ),
            actorUserId = actorId,
        )
        return toResponse(saved, breakdown)
    }

    // --- helpers ---

    /** Carrega o turno e exige que esteja ABERTO (senão 404 inexistente / 409 fechado). */
    private fun loadOpen(sessionId: UUID): CashSession {
        val session = cashSessionRepository.findById(sessionId)
            .orElseThrow { ResourceNotFoundException("Caixa não encontrado") }
        if (session.status != CashSessionStatus.OPEN) {
            throw ConflictException("O caixa já está fechado")
        }
        return session
    }

    private data class Breakdown(
        val cashSales: Long,
        val deposits: Long,
        val withdrawals: Long,
        val expected: Long,
        val entries: List<CashSessionEntry>,
    )

    /**
     * Calcula vendas em dinheiro, reforços, sangrias e o esperado do turno.
     * Aritmética inteira em centavos. As vendas em dinheiro vêm do banco
     * (orders carimbados com o turno, CASH + PAID).
     */
    private fun breakdown(session: CashSession): Breakdown {
        val cashSales = orderRepository.sumCashSalesForSession(session.id!!)
        val entries = entryRepository.findAllBySessionId(session.id!!)
        val deposits = entries.filter { it.type == CashEntryType.DEPOSIT }.sumOf { it.amountCents }
        val withdrawals = entries.filter { it.type == CashEntryType.WITHDRAWAL }.sumOf { it.amountCents }
        val expected = session.openingAmountCents + cashSales + deposits - withdrawals
        return Breakdown(cashSales, deposits, withdrawals, expected, entries)
    }

    private fun toResponse(session: CashSession, bd: Breakdown = breakdown(session)): CashSessionResponse {
        // Diferença calculada em memória (contado - esperado): evita reler a
        // coluna gerada do banco, que ficaria stale na mesma transação após o save.
        val counted = session.closingCountedCents
        val difference = if (counted != null) counted - bd.expected else null
        return CashSessionResponse(
            id = session.id!!,
            status = session.status,
            openedByUserId = session.openedByUserId,
            openedAt = session.openedAt,
            openingAmountCents = session.openingAmountCents,
            closedByUserId = session.closedByUserId,
            closedAt = session.closedAt,
            cashSalesCents = bd.cashSales,
            depositsCents = bd.deposits,
            withdrawalsCents = bd.withdrawals,
            expectedCents = bd.expected,
            countedCents = counted,
            differenceCents = difference,
            entries = bd.entries
                .sortedBy { it.createdAt }
                .map {
                    CashEntryResponse(
                        id = it.id!!,
                        type = it.type,
                        amountCents = it.amountCents,
                        reason = it.reason,
                        createdByUserId = it.createdByUserId,
                        createdAt = it.createdAt,
                    )
                },
            notes = session.notes,
        )
    }
}

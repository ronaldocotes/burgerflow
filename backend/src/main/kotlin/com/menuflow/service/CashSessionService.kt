package com.menuflow.service

import com.menuflow.dto.CashEntryResponse
import com.menuflow.dto.CashSessionResponse
import com.menuflow.dto.CloseSessionRequest
import com.menuflow.dto.EntryRequest
import com.menuflow.dto.OpenSessionRequest
import com.menuflow.dto.PaymentMethodReconciliation
import com.menuflow.dto.ReconciliationMethod
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.exception.UnprocessableEntityException
import com.menuflow.model.CashEntryType
import com.menuflow.model.CashSession
import com.menuflow.model.CashSessionEntry
import com.menuflow.model.CashSessionStatus
import com.menuflow.model.PaymentMethod
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
 * Esperado do DINHEIRO na gaveta = abertura + vendas em dinheiro + reforços - sangrias.
 * A reconciliação de fechamento (issue #1) compara, POR FORMA de pagamento
 * (dinheiro | cartão | pix), o esperado do sistema contra o contado pelo operador,
 * destacando a diferença. Vendas por forma = pedidos carimbados com o turno
 * (cashSessionId), PAID, agrupados por paymentMethod (carimbo em PdvService.pay).
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

    /** Um turno específico (preview de reconciliação se aberto, snapshot se fechado). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(sessionId: UUID): CashSessionResponse {
        val session = cashSessionRepository.findById(sessionId)
            .orElseThrow { ResourceNotFoundException("Caixa não encontrado") }
        return toResponse(session)
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(pageable: Pageable): Page<CashSessionResponse> =
        cashSessionRepository.findAll(pageable).map { toResponse(it) }

    @Transactional("tenantTransactionManager")
    fun open(actorId: UUID, req: OpenSessionRequest): CashSessionResponse {
        // Anti-padrão do benchmark: abrir caixa com R$ 0 sem qualquer trava. Aqui a
        // abertura zerada exige confirmação explícita do cliente (confirmZeroOpening)
        // — evita o "abri sem querer / sem contar o troco". Valor > 0 passa direto.
        if (req.openingAmountCents == 0L && !req.confirmZeroOpening) {
            throw UnprocessableEntityException(
                "Confirme a abertura sem troco inicial (R$ 0,00) enviando confirmZeroOpening=true",
                listOf(mapOf("field" to "openingAmountCents", "value" to 0)),
            )
        }
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

        // O dinheiro retirado no fechamento não pode ser maior do que o contado na
        // gaveta (não dá para tirar o que não há). 422 semântico.
        val withdrawn = req.withdrawnAmountCents ?: 0L
        if (withdrawn > req.countedAmountCents) {
            throw UnprocessableEntityException(
                "O dinheiro retirado no fechamento não pode exceder o contado na gaveta",
                listOf(
                    mapOf(
                        "withdrawnAmountCents" to withdrawn,
                        "countedAmountCents" to req.countedAmountCents,
                    ),
                ),
            )
        }

        val breakdown = breakdown(session)
        // Snapshot da reconciliação por forma (verdade histórica do turno).
        session.closingExpectedCents = breakdown.cashExpected
        session.closingCountedCents = req.countedAmountCents
        session.closingCardExpectedCents = breakdown.cardSales
        session.closingCardCountedCents = req.countedCardCents
        session.closingPixExpectedCents = breakdown.pixSales
        session.closingPixCountedCents = req.countedPixCents
        // Retirada de fechamento e saldo sugerido para a próxima abertura.
        session.withdrawnAtCloseCents = withdrawn
        session.suggestedNextOpeningCents = (req.countedAmountCents - withdrawn).coerceAtLeast(0)

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
                "cashExpectedCents" to breakdown.cashExpected,
                "cashCountedCents" to req.countedAmountCents,
                "cashDifferenceCents" to (req.countedAmountCents - breakdown.cashExpected),
                "cardExpectedCents" to breakdown.cardSales,
                "cardCountedCents" to req.countedCardCents,
                "pixExpectedCents" to breakdown.pixSales,
                "pixCountedCents" to req.countedPixCents,
                "withdrawnAtCloseCents" to withdrawn,
                "suggestedNextOpeningCents" to session.suggestedNextOpeningCents,
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
        val cardSales: Long,
        val pixSales: Long,
        val otherSales: Long,
        val deposits: Long,
        val withdrawals: Long,
        /** Esperado do DINHEIRO na gaveta = abertura + vendas cash + reforços - sangrias. */
        val cashExpected: Long,
        val entries: List<CashSessionEntry>,
    )

    /**
     * Calcula vendas por forma de pagamento, reforços, sangrias e o esperado do
     * dinheiro. Aritmética inteira em centavos. As vendas vêm do banco (orders
     * carimbados com o turno, PAID, agrupados por forma). Cartão soma crédito+débito.
     */
    private fun breakdown(session: CashSession): Breakdown {
        val byMethod: Map<PaymentMethod, Long> = orderRepository
            .sumSalesByMethodForSession(session.id!!)
            .associate { row -> (row[0] as PaymentMethod) to (row[1] as Long) }
        val cashSales = byMethod[PaymentMethod.CASH] ?: 0L
        val cardSales = (byMethod[PaymentMethod.CREDIT_CARD] ?: 0L) + (byMethod[PaymentMethod.DEBIT_CARD] ?: 0L)
        val pixSales = byMethod[PaymentMethod.PIX] ?: 0L
        val otherSales = byMethod[PaymentMethod.OTHER] ?: 0L

        val entries = entryRepository.findAllBySessionId(session.id!!)
        val deposits = entries.filter { it.type == CashEntryType.DEPOSIT }.sumOf { it.amountCents }
        val withdrawals = entries.filter { it.type == CashEntryType.WITHDRAWAL }.sumOf { it.amountCents }
        val cashExpected = session.openingAmountCents + cashSales + deposits - withdrawals
        return Breakdown(cashSales, cardSales, pixSales, otherSales, deposits, withdrawals, cashExpected, entries)
    }

    /** Monta uma linha da reconciliação (diferença = contado - esperado, se contado != null). */
    private fun recRow(method: ReconciliationMethod, expected: Long, counted: Long?) =
        PaymentMethodReconciliation(method, expected, counted, counted?.let { it - expected })

    private fun toResponse(session: CashSession, bd: Breakdown = breakdown(session)): CashSessionResponse {
        val isClosed = session.status == CashSessionStatus.CLOSED
        // Diferença do dinheiro em memória (contado - esperado): evita reler a coluna
        // gerada do banco, que ficaria stale na mesma transação após o save.
        val cashCounted = session.closingCountedCents
        // Esperado por forma: fechado usa o snapshot (verdade histórica, com fallback
        // ao cálculo vivo se coluna nula em dado antigo); aberto usa o cálculo vivo.
        val cashExpected = if (isClosed) session.closingExpectedCents ?: bd.cashExpected else bd.cashExpected
        val cardExpected = if (isClosed) session.closingCardExpectedCents ?: bd.cardSales else bd.cardSales
        val pixExpected = if (isClosed) session.closingPixExpectedCents ?: bd.pixSales else bd.pixSales

        val reconciliation = buildList {
            add(recRow(ReconciliationMethod.CASH, cashExpected, cashCounted))
            add(recRow(ReconciliationMethod.CARD, cardExpected, session.closingCardCountedCents))
            add(recRow(ReconciliationMethod.PIX, pixExpected, session.closingPixCountedCents))
            // Forma "outros" só aparece se houver venda nela (não polui a tabela padrão).
            if (bd.otherSales > 0) add(recRow(ReconciliationMethod.OTHER, bd.otherSales, null))
        }

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
            expectedCents = cashExpected,
            countedCents = cashCounted,
            differenceCents = cashCounted?.let { it - cashExpected },
            reconciliation = reconciliation,
            withdrawnAtCloseCents = session.withdrawnAtCloseCents,
            suggestedNextOpeningCents = session.suggestedNextOpeningCents,
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

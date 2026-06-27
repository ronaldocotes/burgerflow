package com.menuflow.service

import com.menuflow.dto.TableCreateRequest
import com.menuflow.dto.TableDto
import com.menuflow.dto.TableSessionView
import com.menuflow.dto.TableUpdateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.OrderStatus
import com.menuflow.model.RestaurantTable
import com.menuflow.model.TableSession
import com.menuflow.model.TableSessionStatus
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.RestaurantTableRepository
import com.menuflow.repository.tenant.TableSessionRepository
import com.menuflow.tenant.TenantContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Mesas e Comandas. Tudo no banco do TENANT (escopo garantido pelo datasource
 * roteado). Cada mutação de estado publica o novo estado da mesa no tópico STOMP
 * /topic/tables/{tenant} para os salões/garçons verem ao vivo.
 *
 * Invariante central: no máximo UMA comanda ativa (OPEN/BILLING) por mesa —
 * garantida pela checagem em [openSession] + índice parcial uq_session_active_per_table.
 */
@Service
class TableService(
    private val tableRepository: RestaurantTableRepository,
    private val sessionRepository: TableSessionRepository,
    private val orderRepository: OrderRepository,
    private val realtimePublisher: RealtimePublisher,
) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun listTables(): List<TableDto> =
        tableRepository.findByActiveTrueOrderBySortOrderAscLabelAsc()
            .map { it.toDto(activeSession(it.id!!)) }

    @Transactional("tenantTransactionManager")
    fun createTable(req: TableCreateRequest): TableDto {
        val label = req.label.trim()
        if (tableRepository.existsByLabelAndActiveTrue(label)) {
            throw ConflictException("Já existe uma mesa ativa com o rótulo '$label'")
        }
        val saved = tableRepository.save(
            RestaurantTable(label = label, seats = req.seats, sortOrder = req.sortOrder),
        )
        return saved.toDto(null)
    }

    @Transactional("tenantTransactionManager")
    fun updateTable(id: UUID, req: TableUpdateRequest): TableDto {
        val table = tableRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Mesa não encontrada") }
        req.label?.let { raw ->
            val label = raw.trim()
            if (label != table.label && tableRepository.existsByLabelAndActiveTrue(label)) {
                throw ConflictException("Já existe uma mesa ativa com o rótulo '$label'")
            }
            table.label = label
        }
        req.seats?.let { table.seats = it }
        req.sortOrder?.let { table.sortOrder = it }
        req.active?.let { table.active = it }
        val saved = tableRepository.save(table)
        return saved.toDto(activeSession(saved.id!!))
    }

    @Transactional("tenantTransactionManager")
    fun openSession(tableId: UUID, userId: UUID?): TableDto {
        val table = tableRepository.findById(tableId)
            .orElseThrow { ResourceNotFoundException("Mesa não encontrada") }
        if (!table.active) throw BusinessException("Mesa inativa não pode abrir comanda")
        sessionRepository.findActiveByTableId(tableId).ifPresent {
            throw ConflictException("Já existe uma comanda aberta nesta mesa")
        }
        val session = sessionRepository.save(
            TableSession(table = table, status = TableSessionStatus.OPEN, openedByUserId = userId),
        )
        return publishAndReturn(table, session)
    }

    @Transactional("tenantTransactionManager")
    fun requestBill(tableId: UUID, userId: UUID?): TableDto {
        val session = sessionRepository.findActiveByTableId(tableId)
            .orElseThrow { BusinessException("Não há comanda aberta nesta mesa") }
        if (session.status != TableSessionStatus.OPEN) {
            throw BusinessException("Só é possível pedir a conta de uma comanda ABERTA")
        }
        session.status = TableSessionStatus.BILLING
        session.billRequestedAt = Instant.now()
        val saved = sessionRepository.save(session)
        return publishAndReturn(saved.table, saved)
    }

    @Transactional("tenantTransactionManager")
    fun closeSession(tableId: UUID, userId: UUID?): TableDto {
        val session = sessionRepository.findActiveByTableId(tableId)
            .orElseThrow { BusinessException("Não há comanda aberta nesta mesa") }
        // Não fecha a conta enquanto a cozinha tiver pedidos em produção.
        if (orderRepository.existsByTableSession_IdAndStatusIn(
                session.id!!, listOf(OrderStatus.PENDING, OrderStatus.PREPARING),
            )
        ) {
            throw BusinessException("Há pedidos em aberto na cozinha")
        }
        session.status = TableSessionStatus.CLOSED
        session.closedAt = Instant.now()
        session.closedByUserId = userId
        val saved = sessionRepository.save(session)
        // Broadcast: a mesa ficou livre (sem sessão ativa).
        realtimePublisher.publishTables(TenantContext.getOrThrow(), saved.table.toDto(null))
        // Resposta ao chamador: a sessão recém-fechada (status CLOSED) como confirmação.
        return saved.table.toDto(saved)
    }

    // --- helpers ---

    private fun activeSession(tableId: UUID): TableSession? =
        sessionRepository.findActiveByTableId(tableId).orElse(null)

    /** Atualiza o estado da mesa no STOMP e devolve o DTO correspondente. */
    private fun publishAndReturn(table: RestaurantTable, session: TableSession?): TableDto {
        val dto = table.toDto(session)
        realtimePublisher.publishTables(TenantContext.getOrThrow(), dto)
        return dto
    }

    private fun RestaurantTable.toDto(session: TableSession?) = TableDto(
        id = id!!,
        label = label,
        seats = seats,
        sortOrder = sortOrder,
        active = active,
        session = session?.let {
            TableSessionView(
                sessionId = it.id!!,
                status = it.status.name,
                openedAt = it.openedAt,
                billRequestedAt = it.billRequestedAt,
            )
        },
    )
}

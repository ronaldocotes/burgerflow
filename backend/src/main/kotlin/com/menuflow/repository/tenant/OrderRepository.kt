package com.menuflow.repository.tenant

import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface OrderRepository :
    JpaRepository<Order, UUID>,
    JpaSpecificationExecutor<Order> {

    fun findByOrderNumber(orderNumber: String): Order?

    /**
     * Há pedido da comanda ainda em produção (PENDING/PREPARING)? Usado por
     * TableService.closeSession para impedir fechar a conta com a cozinha aberta.
     */
    fun existsByTableSession_IdAndStatusIn(sessionId: UUID, statuses: Collection<OrderStatus>): Boolean

    /**
     * KDS feed: active kitchen orders ordered by age (oldest first). The status
     * set is passed in so callers choose the scope (KDS = PENDING+PREPARING).
     */
    fun findByStatusInOrderByCreatedAtAsc(statuses: Collection<OrderStatus>): List<Order>

    /** Active kitchen orders since a timestamp (the day's start), oldest first. */
    fun findByStatusInAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
        statuses: Collection<OrderStatus>,
        from: Instant,
    ): List<Order>

    /**
     * KDS board feed (3 columns: Novos/PENDING · Em preparo/PREPARING · Prontos/READY).
     * PENDING + PREPARING are always shown regardless of age (a kitchen ticket must
     * never silently vanish), while READY is limited to [readyFrom] (start of the
     * business day in São Paulo) so the "Prontos" column is not polluted with
     * previous days' finished orders. Oldest first.
     */
    @Query(
        """
        SELECT o FROM Order o
        WHERE o.status IN (com.menuflow.model.OrderStatus.PENDING, com.menuflow.model.OrderStatus.PREPARING)
           OR (o.status = com.menuflow.model.OrderStatus.READY AND o.createdAt >= :readyFrom)
        ORDER BY o.createdAt ASC
        """,
    )
    fun findKdsBoardOrders(@Param("readyFrom") readyFrom: Instant): List<Order>

    /**
     * Active delivery orders of the day that already have a courier assigned:
     * deliveryStatus is set and not yet terminal (DELIVERED), within the window.
     */
    @Query(
        """
        SELECT o FROM Order o
        WHERE o.driverId IS NOT NULL
          AND o.deliveryStatus IS NOT NULL
          AND o.deliveryStatus <> com.menuflow.model.DeliveryStatus.DELIVERED
          AND o.createdAt >= :from
        ORDER BY o.createdAt ASC
        """,
    )
    fun findActiveDeliveryOrders(@Param("from") from: Instant): List<Order>

    /**
     * Soma (centavos) das vendas em dinheiro EFETIVADAS de um turno de caixa:
     * pedidos carimbados com o turno, pagos em dinheiro (CASH) e com pagamento
     * confirmado (PAID). COALESCE garante 0 quando não há nenhuma venda. Entra no
     * cálculo do esperado da gaveta no fechamento do caixa.
     */
    @Query(
        """
        SELECT COALESCE(SUM(o.totalCents), 0) FROM Order o
        WHERE o.cashSessionId = :sessionId
          AND o.paymentMethod = com.menuflow.model.PaymentMethod.CASH
          AND o.paymentStatus = com.menuflow.model.PaymentStatus.PAID
        """,
    )
    fun sumCashSalesForSession(@Param("sessionId") sessionId: UUID): Long

    /**
     * Conta as entregas REALIZADAS de um entregador num periodo, para o acerto
     * financeiro (Fase 2.5): pedidos carimbados com o entregador (driver_id),
     * concluidos (status DELIVERED) e completados dentro da janela [from, to).
     * O Order nao tem coluna deliveredAt — completedAt e setado na transicao para
     * DELIVERED (OrderService.updateStatus / PdvService.pay), entao serve de
     * carimbo temporal da entrega. Os limites instantaneos vem do servico (dia em
     * America/Sao_Paulo), evitando timezone dentro do SQL.
     */
    @Query(
        """
        SELECT COUNT(o) FROM Order o
        WHERE o.driverId = :driverId
          AND o.status = com.menuflow.model.OrderStatus.DELIVERED
          AND o.completedAt >= :from
          AND o.completedAt < :to
        """,
    )
    fun countDeliveriesByDriverAndPeriod(
        @Param("driverId") driverId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long

    // --- DRE Automático (Fase 3.1) ---

    /**
     * Agregados do DRE para os pedidos DELIVERED concluídos no período [from, to):
     * soma de total, taxas de marketplace e cartão, CMV (cogs) e a CONTAGEM de
     * pedidos — nessa ordem. COALESCE garante 0 em período vazio (sem NULL/NPE).
     * Retorna exatamente UMA linha (sem GROUP BY) como List<Object[]> de um
     * elemento; o serviço pega .first() e converte cada coluna em Long.
     * completedAt é o carimbo temporal da venda concluída.
     */
    @Query(
        """
        SELECT COALESCE(SUM(o.totalCents), 0),
               COALESCE(SUM(o.marketplaceFeeCents), 0),
               COALESCE(SUM(o.cardFeeCents), 0),
               COALESCE(SUM(o.cogsCents), 0),
               COUNT(o)
        FROM Order o
        WHERE o.status = com.menuflow.model.OrderStatus.DELIVERED
          AND o.completedAt >= :from AND o.completedAt < :to
        """,
    )
    fun dreAggregate(@Param("from") from: Instant, @Param("to") to: Instant): List<Array<Any>>

    /**
     * Quantidade de pedidos DELIVERED por canal de venda no período. Cada linha é
     * [SalesChannel, COUNT]. Usado no recorte "pedidos por canal" do DRE.
     */
    @Query(
        """
        SELECT o.salesChannel, COUNT(o) FROM Order o
        WHERE o.status = com.menuflow.model.OrderStatus.DELIVERED
          AND o.completedAt >= :from AND o.completedAt < :to
        GROUP BY o.salesChannel
        """,
    )
    fun countByChannel(@Param("from") from: Instant, @Param("to") to: Instant): List<Array<Any>>

    /**
     * Quantidade de pedidos DELIVERED por forma de pagamento no período. Cada linha
     * é [PaymentMethod, COUNT]; paymentMethod pode ser null (entrega concluída sem
     * pagamento registrado) -> o serviço agrupa como "UNKNOWN".
     */
    @Query(
        """
        SELECT o.paymentMethod, COUNT(o) FROM Order o
        WHERE o.status = com.menuflow.model.OrderStatus.DELIVERED
          AND o.completedAt >= :from AND o.completedAt < :to
        GROUP BY o.paymentMethod
        """,
    )
    fun countByPaymentMethod(@Param("from") from: Instant, @Param("to") to: Instant): List<Array<Any>>
}

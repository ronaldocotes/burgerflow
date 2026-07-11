package com.menuflow.repository.tenant

import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import org.springframework.data.domain.Pageable
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
     * Candidatos ao despacho por grupo (Fase B1): pedidos de DELIVERY PAGOS, ainda em
     * producao (PENDING/PREPARING), SEM motoboy atribuido e SEM oferta OFFERED viva.
     * O corte fino de tempo (updatedAt + prepTime - leadMinutes) e feito em memoria no
     * DispatchService, pois depende de config por tenant. Sem endereco/geocode nao ha
     * problema aqui: o pedido entra e o DispatchService cai no fee do pedido.
     */
    @Query(
        """
        SELECT o FROM Order o
        WHERE o.orderType = com.menuflow.model.OrderType.DELIVERY
          AND o.paymentStatus = com.menuflow.model.PaymentStatus.PAID
          AND o.status IN (com.menuflow.model.OrderStatus.PENDING, com.menuflow.model.OrderStatus.PREPARING)
          AND o.driverId IS NULL
          AND NOT EXISTS (
            SELECT of FROM DeliveryOffer of
            WHERE of.orderId = o.id
              AND of.status = com.menuflow.model.DeliveryOfferStatus.OFFERED
          )
        ORDER BY o.updatedAt ASC
        """,
    )
    fun findDispatchEligibleOrders(): List<Order>

    /** Pedidos mais recentes (qualquer status), para a tool get_recent_orders do Copiloto. */
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<Order>

    /**
     * Ultimo pedido feito por um telefone (bot WhatsApp, Fase 4.3). O telefone vem do
     * remetente VERIFICADO do webhook (nunca do LLM) — o cliente so ve o proprio pedido.
     */
    fun findTopByCustomerPhoneOrderByCreatedAtDesc(customerPhone: String): Order?

    /**
     * Ultimo pedido de ENTREGA em andamento para o telefone (Fase D — track_order do bot).
     * Filtra: deliveryStatus preenchido (pedido ja tem motoboy ou foi despachado) e nao CANCELADO.
     * O telefone e o do remetente VERIFICADO do webhook.
     */
    fun findFirstByCustomerPhoneAndDeliveryStatusIsNotNullAndStatusNotOrderByCreatedAtDesc(
        customerPhone: String,
        status: OrderStatus,
    ): Order?

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
     * Pedidos de entrega ATIVOS atribuidos a um entregador (Fase 6.1 — GET
     * /delivery/orders/my). deliveryStatus setado e ainda nao terminal (DELIVERED
     * ou FAILED). Mais recentes primeiro.
     */
    @Query(
        """
        SELECT o FROM Order o
        WHERE o.driverId = :driverId
          AND o.deliveryStatus IS NOT NULL
          AND o.deliveryStatus <> com.menuflow.model.DeliveryStatus.DELIVERED
          AND o.deliveryStatus <> com.menuflow.model.DeliveryStatus.FAILED
        ORDER BY o.updatedAt DESC
        """,
    )
    fun findActiveOrdersForDriver(@Param("driverId") driverId: UUID): List<Order>

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
     * Vendas EFETIVADAS (PAID) de um turno agrupadas por forma de pagamento, para a
     * reconciliação de fechamento por forma (dinheiro | cartão | pix). Só entra o
     * que foi carimbado com o turno (cashSessionId) no momento da venda — no PDV o
     * carimbo acontece em PdvService.pay para CASH, CARD e PIX. Retorna pares
     * [PaymentMethod, somaCentavos]; formas sem venda simplesmente não aparecem
     * (o serviço assume 0). Agregado multi-coluna → List<Array<Any>> (o primeiro
     * elemento é o enum PaymentMethod, o segundo o Long da soma).
     */
    @Query(
        """
        SELECT o.paymentMethod, COALESCE(SUM(o.totalCents), 0) FROM Order o
        WHERE o.cashSessionId = :sessionId
          AND o.paymentStatus = com.menuflow.model.PaymentStatus.PAID
          AND o.paymentMethod IS NOT NULL
        GROUP BY o.paymentMethod
        """,
    )
    fun sumSalesByMethodForSession(@Param("sessionId") sessionId: UUID): List<Array<Any>>

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

    /**
     * Soma a distancia rodoviaria (metros) das entregas DELIVERED de um entregador no
     * periodo (issue #3, eixo por-km da FROTA). Ignora pedidos sem distancia registrada
     * (frete por zona/linha reta => delivery_distance_meters NULL). COALESCE garante 0
     * em periodo vazio. Os limites [from, to) vem do servico (dia em America/Sao_Paulo).
     */
    @Query(
        """
        SELECT COALESCE(SUM(o.deliveryDistanceMeters), 0) FROM Order o
        WHERE o.driverId = :driverId
          AND o.status = com.menuflow.model.OrderStatus.DELIVERED
          AND o.completedAt >= :from
          AND o.completedAt < :to
          AND o.deliveryDistanceMeters IS NOT NULL
        """,
    )
    fun sumDeliveryDistanceByDriverAndPeriod(
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
               COUNT(o),
               COALESCE(SUM(o.couponDiscountCents), 0),
               COALESCE(SUM(o.discountCents), 0)
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

    // --- RFV (Fase 3.4) ---

    /**
     * Agregado RFV por cliente, sobre os pedidos NAO cancelados. Cada linha e
     * [customerId, lastOrder(Instant), freq90(Long), sum90(Long), lifetimeCount(Long)]:
     *  - lastOrder = MAX(createdAt) de TODA a historia (para a recencia);
     *  - freq90/sum90 = contagem/soma SOMENTE dentro da janela [windowStart, agora]
     *    (agregacao condicional), para frequencia e ticket medio;
     *  - lifetimeCount = total de pedidos na vida (NEW = exatamente 1).
     * So entram clientes identificados (customerId nao nulo). O servico converte e
     * classifica em memoria (RfvService.classify).
     */
    @Query(
        """
        SELECT o.customerId,
               MAX(o.createdAt),
               COALESCE(SUM(CASE WHEN o.createdAt >= :windowStart THEN 1L ELSE 0L END), 0L),
               COALESCE(SUM(CASE WHEN o.createdAt >= :windowStart THEN o.totalCents ELSE 0L END), 0L),
               COUNT(o)
        FROM Order o
        WHERE o.customerId IS NOT NULL
          AND o.status <> com.menuflow.model.OrderStatus.CANCELLED
        GROUP BY o.customerId
        """,
    )
    fun rfvAggregate(@Param("windowStart") windowStart: Instant): List<Array<Any>>
}

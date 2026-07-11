package com.menuflow.model

import com.menuflow.channels.ChannelType
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Order lives in the TENANT database. All monetary fields are in CENTAVOS.
 */
@Entity
@Table(name = "orders")
data class Order(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "order_number", nullable = false, unique = true)
    var orderNumber: String,

    @Column(name = "customer_id")
    var customerId: UUID? = null,

    /**
     * Telefone do cliente para notificacao por WhatsApp (Fase 2.4). Opt-in por
     * pedido: quando preenchido, os marcos de status disparam um aviso via WAHA.
     */
    @Column(name = "customer_phone", length = 20)
    var customerPhone: String? = null,

    @Column(name = "user_id")
    var userId: UUID? = null,

    @Column(name = "order_type", nullable = false)
    @Enumerated(EnumType.STRING)
    var orderType: OrderType = OrderType.DINE_IN,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.PENDING,

    @Column(name = "table_number")
    var tableNumber: String? = null,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "subtotal_cents", nullable = false)
    var subtotalCents: Long = 0,

    @Column(name = "discount_cents", nullable = false)
    var discountCents: Long = 0,

    @Column(name = "delivery_fee_cents", nullable = false)
    var deliveryFeeCents: Long = 0,

    @Column(name = "total_cents", nullable = false)
    var totalCents: Long = 0,

    @Column(name = "payment_method")
    @Enumerated(EnumType.STRING)
    var paymentMethod: PaymentMethod? = null,

    @Column(name = "payment_status", nullable = false)
    @Enumerated(EnumType.STRING)
    var paymentStatus: PaymentStatus = PaymentStatus.PENDING,

    @Column(name = "priority", nullable = false)
    @Enumerated(EnumType.STRING)
    var priority: OrderPriority = OrderPriority.NORMAL,

    @Column(name = "estimated_prep_time_minutes", nullable = false)
    var estimatedPrepTimeMinutes: Int = 15,

    @OneToMany(mappedBy = "orderId", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var items: MutableList<OrderItem> = mutableListOf(),

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,

    @Column(name = "cancelled_reason")
    var cancelledReason: String? = null,

    /** Motivo pre-cadastrado escolhido (issue #10); o texto fica denormalizado em
     * [cancelledReason]. Null = motivo livre/legado. */
    @Column(name = "cancelled_reason_id")
    var cancelledReasonId: UUID? = null,

    // --- Sprint 2: delivery dispatch (tenant DB) ---
    /** Assigned delivery courier (DeliveryDriver.id in the tenant DB), if any. */
    @Column(name = "driver_id")
    var driverId: UUID? = null,

    /** Delivery dispatch status; null until the order is assigned to a driver. */
    @Column(name = "delivery_status")
    @Enumerated(EnumType.STRING)
    var deliveryStatus: DeliveryStatus? = null,

    // --- Módulo Mesas e Comandas (tenant DB) ---
    /**
     * Comanda (TableSession) à qual o pedido pertence; null para pedidos de
     * balcão/delivery sem mesa. Fechar a comanda exige que nenhum pedido seu
     * esteja PENDING/PREPARING (ver TableService.closeSession).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_session_id")
    var tableSession: TableSession? = null,

    // --- Módulo Turno de Caixa (tenant DB) ---
    /**
     * Turno de caixa (CashSession) ao qual a venda em dinheiro pertence; null para
     * pedidos sem caixa (cardápio público, cartão, pix). Carimbado em
     * OrderService.create quando o operador autenticado vende em dinheiro, e somado
     * no esperado do turno quando o pedido fica PAID.
     */
    @Column(name = "cash_session_id")
    var cashSessionId: UUID? = null,

    // --- DRE Automático (Fase 3.1) — snapshots de custo/taxa gravados na venda ---
    /**
     * Canal de venda do pedido, para o recorte do DRE. Derivado no
     * OrderService.create (público=ONLINE; operador: DELIVERY/DINE_IN/COUNTER por
     * orderType). Default COUNTER nos pedidos antigos (backfill na V22).
     */
    @Column(name = "sales_channel", nullable = false)
    @Enumerated(EnumType.STRING)
    var salesChannel: SalesChannel = SalesChannel.COUNTER,

    /** Snapshot do CMV (custo da mercadoria) no momento da venda, em centavos. */
    @Column(name = "cogs_cents", nullable = false)
    var cogsCents: Long = 0,

    /** Taxa de marketplace (iFood/Rappi) calculada na venda, em centavos. */
    @Column(name = "marketplace_fee_cents", nullable = false)
    var marketplaceFeeCents: Long = 0,

    /** Taxa de cartão calculada no pagamento, em centavos. */
    @Column(name = "card_fee_cents", nullable = false)
    var cardFeeCents: Long = 0,

    // --- Cupons & Descontos (Fase 3.2) — snapshot do cupom usado na venda ---
    /** Cupom aplicado ao pedido (Coupon.id no banco do tenant); null se nenhum. */
    @Column(name = "coupon_id")
    var couponId: UUID? = null,

    /** Snapshot do código do cupom usado (preserva mesmo que o cupom mude depois). */
    @Column(name = "coupon_code", length = 50)
    var couponCode: String? = null,

    /**
     * Desconto abatido pelo cupom, em centavos. Já está incluído em [discountCents]
     * (cupom sobrescreve o desconto manual); este campo é o recorte só-do-cupom.
     */
    @Column(name = "coupon_discount_cents", nullable = false)
    var couponDiscountCents: Long = 0,

    // --- Multicanal (Fase 5.0) — plataforma de ORIGEM do pedido ---
    /**
     * Plataforma de origem (ChannelType.name): OWN/IFOOD/RAPPI. Default 'OWN' para
     * todo pedido nascido no MenuFlow (PDV, cardápio público, app). Não confundir
     * com [salesChannel] (recorte interno do DRE).
     */
    @Column(name = "external_origin", nullable = false)
    var externalOrigin: String = ChannelType.OWN.name,

    /** Id do pedido na plataforma externa (iFood/Rappi); null para canal próprio. */
    @Column(name = "external_order_id")
    var externalOrderId: String? = null,

    /** Número exibido ao cliente na plataforma externa (ex.: "#4502" do iFood). */
    @Column(name = "external_display_id")
    var externalDisplayId: String? = null,

    // --- Fase 6.1: endereço e geolocalização da entrega (tenant DB) ---
    // Todos nullable: pedido de balcão/mesa não tem endereço; o geocode (lat/lng)
    // pode não estar disponível na criação. Snapshot no pedido (não referencia o
    // cadastro de cliente) para o histórico de entrega ser imutável.
    @Column(name = "delivery_recipient_name", length = 120)
    var deliveryRecipientName: String? = null,

    @Column(name = "delivery_phone", length = 20)
    var deliveryPhone: String? = null,

    @Column(name = "delivery_cep", length = 9)
    var deliveryCep: String? = null,

    @Column(name = "delivery_street", length = 200)
    var deliveryStreet: String? = null,

    @Column(name = "delivery_number", length = 20)
    var deliveryNumber: String? = null,

    @Column(name = "delivery_complement", length = 100)
    var deliveryComplement: String? = null,

    @Column(name = "delivery_neighborhood", length = 100)
    var deliveryNeighborhood: String? = null,

    @Column(name = "delivery_city", length = 100)
    var deliveryCity: String? = null,

    @Column(name = "delivery_reference", length = 200)
    var deliveryReference: String? = null,

    /** Latitude do endereço de entrega (geocode). Requisito do auto-assign. */
    @Column(name = "delivery_lat")
    var deliveryLat: Double? = null,

    /** Longitude do endereço de entrega (geocode). */
    @Column(name = "delivery_lng")
    var deliveryLng: Double? = null,

    /** Origem da coordenada (VIACEP/GOOGLE/MANUAL) para auditoria. */
    @Column(name = "delivery_geocode_source", length = 30)
    var deliveryGeocodeSource: String? = null,

    /**
     * Distancia rodoviaria (metros) calculada na precificacao do frete (issue #3, G3).
     * Persistida para alimentar o eixo por-km do acerto da FROTA. NULL quando o frete
     * veio por zona/linha reta ou nao houve geocode — nesse caso o acerto usa o
     * override manual do request ou 0.
     */
    @Column(name = "delivery_distance_meters")
    var deliveryDistanceMeters: Long? = null,
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    /**
     * Plataforma de origem como enum. Tolerante a valor desconhecido em
     * external_origin (cai em OWN) para nunca quebrar o fluxo de pedido.
     */
    fun channelType(): ChannelType =
        runCatching { ChannelType.valueOf(externalOrigin) }.getOrDefault(ChannelType.OWN)

    fun canBeCancelled(): Boolean =
        status in listOf(OrderStatus.PENDING, OrderStatus.PREPARING)
}

enum class OrderType {
    DINE_IN,
    TAKEAWAY,
    DELIVERY,
}

/**
 * Lifecycle per Sprint 1 spec: PENDING -> PREPARING -> READY -> DELIVERED,
 * with CANCELLED reachable from any non-terminal state.
 */
enum class OrderStatus {
    PENDING,
    PREPARING,
    READY,
    DELIVERED,
    CANCELLED,
}

enum class PaymentMethod {
    CASH,
    CREDIT_CARD,
    DEBIT_CARD,
    PIX,
    OTHER,
}

enum class PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED,
}

enum class OrderPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT,
}

/**
 * Ciclo de vida do despacho de entrega, independente do [OrderStatus] da cozinha.
 *
 * Valores do Sprint 2 (mantidos por compatibilidade): ASSIGNED, OUT_FOR_DELIVERY,
 * DELIVERED. A Fase 6.1 adiciona o fluxo de auto-assign/motoboy de forma ADITIVA:
 * PENDING (sem motoboy) -> OFFERED (oferta enviada) -> ACCEPTED (aceita) ->
 * ARRIVED_AT_STORE -> PICKED_UP -> OUT_FOR_DELIVERY -> ARRIVED_AT_CUSTOMER ->
 * DELIVERED, com FAILED para falha de entrega. Nenhum valor antigo foi removido.
 */
enum class DeliveryStatus {
    // Sprint 2 (mantidos)
    ASSIGNED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    // Fase 6.1 (novos, aditivos)
    PENDING,
    OFFERED,
    ACCEPTED,
    ARRIVED_AT_STORE,
    PICKED_UP,
    ARRIVED_AT_CUSTOMER,
    FAILED,
}

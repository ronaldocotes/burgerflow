package com.menuflow.service

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderResponse
import com.menuflow.dto.OrderStatusUpdateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.exception.UnprocessableEntityException
import com.menuflow.model.Order
import com.menuflow.model.OrderItem
import com.menuflow.model.OrderItemOption
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.repository.tenant.IngredientRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.ProductIngredientRepository
import com.menuflow.repository.tenant.ProductOptionGroupRepository
import com.menuflow.repository.tenant.ProductOptionRepository
import com.menuflow.repository.tenant.ProductRepository
import com.menuflow.tenant.TenantContext
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val productRepository: ProductRepository,
    private val productIngredientRepository: ProductIngredientRepository,
    private val ingredientRepository: IngredientRepository,
    private val productOptionGroupRepository: ProductOptionGroupRepository,
    private val productOptionRepository: ProductOptionRepository,
    private val realtimePublisher: com.menuflow.service.RealtimePublisher,
) {

    private val dateFmt = DateTimeFormatter.ofPattern("yyMMdd")
    private val saoPaulo = ZoneId.of("America/Sao_Paulo")

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(status: OrderStatus?, from: Instant?, to: Instant?, pageable: Pageable): Page<OrderResponse> {
        // Build only the predicates that are present, so a null filter never binds
        // a NULL parameter (Postgres cannot infer the type of a NULL-only param).
        val spec = Specification<Order> { root, _, cb ->
            val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
            status?.let { predicates.add(cb.equal(root.get<OrderStatus>("status"), it)) }
            from?.let { predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), it)) }
            to?.let { predicates.add(cb.lessThan(root.get("createdAt"), it)) }
            cb.and(*predicates.toTypedArray())
        }
        return orderRepository.findAll(spec, pageable).map { OrderResponse.from(it) }
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(id: UUID): OrderResponse =
        OrderResponse.from(
            orderRepository.findById(id).orElseThrow { ResourceNotFoundException("Order not found: $id") },
        )

    /**
     * Creates an order ATOMICALLY: builds items from current product prices,
     * decrements ingredient stock via the ficha técnica under a pessimistic lock,
     * and persists order + items. Insufficient stock aborts the whole transaction
     * with 422 (nothing is written). Money is in centavos throughout.
     */
    @Transactional("tenantTransactionManager")
    fun create(req: OrderCreateRequest, userId: UUID?): OrderResponse {
        if (req.items.isEmpty()) throw BusinessException("Order must have at least one item")

        // 1. Resolve products and build line items (price snapshot).
        val items = mutableListOf<OrderItem>()
        // Snapshots de complementos por linha (index); anexados após o item ter id.
        val optionsByIndex = HashMap<Int, List<OrderItemOption>>()
        var subtotal = 0L
        // Aggregate required ingredient quantities across all order lines.
        val required = HashMap<UUID, Double>()

        req.items.forEachIndexed { index, line ->
            val product = productRepository.findByIdAndActiveTrue(line.productId)
                ?: throw ResourceNotFoundException("Product not found: ${line.productId}")

            // Complementos: valida pertinência ao produto + regras min/max e faz o
            // snapshot (nome+preço); o adicional entra no preço unitário do item.
            val snapshots = resolveOptions(product.id!!, line.optionIds)
            val unitPrice = product.priceCents + snapshots.sumOf { it.priceCents }
            val lineTotal = unitPrice * line.quantity
            subtotal += lineTotal
            items.add(
                OrderItem(
                    orderId = PLACEHOLDER, // set after order is persisted
                    productId = product.id!!,
                    productSku = product.sku,
                    productName = product.name,
                    quantity = line.quantity,
                    unitPriceCents = unitPrice,
                    totalPriceCents = lineTotal,
                    notes = line.notes,
                    displayOrder = index,
                ),
            )
            if (snapshots.isNotEmpty()) optionsByIndex[index] = snapshots
            // Ficha técnica: accumulate ingredient consumption.
            productIngredientRepository.findByProductId(product.id!!).forEach { pi ->
                required.merge(pi.ingredientId, pi.quantity * line.quantity, Double::plus)
            }
        }

        // 2. Decrement stock atomically under a pessimistic lock.
        if (required.isNotEmpty()) {
            val locked = ingredientRepository.findAllByIdsForUpdate(required.keys)
            val byId = locked.associateBy { it.id }
            val shortfalls = mutableListOf<Map<String, Any?>>()
            required.forEach { (ingredientId, need) ->
                val ing = byId[ingredientId]
                    ?: throw ResourceNotFoundException("Ingredient not found: $ingredientId")
                if (ing.stockQuantity < need) {
                    shortfalls.add(
                        mapOf(
                            "ingredientId" to ingredientId,
                            "ingredientName" to ing.name,
                            "required" to need,
                            "available" to ing.stockQuantity,
                            "unit" to ing.unit.name,
                        ),
                    )
                }
            }
            if (shortfalls.isNotEmpty()) {
                throw UnprocessableEntityException("Insufficient ingredient stock", shortfalls)
            }
            // All checks passed -> apply decrements.
            required.forEach { (ingredientId, need) ->
                val ing = byId[ingredientId]!!
                ing.stockQuantity -= need
                ingredientRepository.save(ing)
            }
        }

        // 3. Compute totals (centavos) and persist the order.
        val deliveryFee = if (req.orderType == OrderType.DELIVERY) req.deliveryFeeCents else 0L
        val total = (subtotal - req.discountCents + deliveryFee).coerceAtLeast(0)

        val order = Order(
            orderNumber = generateOrderNumber(),
            customerId = req.customerId,
            userId = userId,
            orderType = req.orderType,
            status = OrderStatus.PENDING,
            tableNumber = req.tableNumber,
            notes = req.notes,
            subtotalCents = subtotal,
            discountCents = req.discountCents,
            deliveryFeeCents = deliveryFee,
            totalCents = total,
            paymentMethod = req.paymentMethod,
            estimatedPrepTimeMinutes = (req.items.sumOf { it.quantity } * 5).coerceAtLeast(10),
        )
        val saved = orderRepository.save(order)
        items.forEach { it.orderId = saved.id!! }
        saved.items.addAll(items)
        val persisted = orderRepository.save(saved)
        // Com os itens já persistidos (cada um com id), anexa os complementos
        // (snapshot) e salva em cascata — orderItemId só pode ser preenchido aqui.
        if (optionsByIndex.isNotEmpty()) {
            persisted.items.forEach { item ->
                val opts = optionsByIndex[item.displayOrder].orEmpty()
                opts.forEach { it.orderItemId = item.id!! }
                item.options.addAll(opts)
            }
            return OrderResponse.from(orderRepository.save(persisted))
        }
        return OrderResponse.from(persisted)
    }

    /**
     * Resolve as opções escolhidas em snapshots, validando: existência/ativação,
     * pertinência a um grupo ATIVO do produto e as regras min/max de cada grupo
     * (grupo obrigatório não satisfeito aborta o pedido).
     */
    private fun resolveOptions(productId: UUID, optionIds: List<UUID>): List<OrderItemOption> {
        val groups = productOptionGroupRepository.findByProductIdAndActiveTrue(productId)
        val wanted = optionIds.toSet()
        val chosen = if (wanted.isEmpty()) emptyList()
            else productOptionRepository.findAllById(wanted).toList()
        if (chosen.size != wanted.size) throw BusinessException("Opção de complemento inválida")

        val groupsById = groups.associateBy { it.id }
        chosen.forEach { opt ->
            if (!opt.active) throw BusinessException("Opção de complemento indisponível")
            groupsById[opt.groupId] ?: throw BusinessException("Opção não pertence a este produto")
        }
        val countByGroup = chosen.groupBy { it.groupId }.mapValues { it.value.size }
        groups.forEach { g ->
            val count = countByGroup[g.id] ?: 0
            if (count < g.minSelect) {
                throw BusinessException("Complemento obrigatório '${g.name}' exige ao menos ${g.minSelect} opção(ões)")
            }
            if (count > g.maxSelect) {
                throw BusinessException("Complemento '${g.name}' aceita no máximo ${g.maxSelect} opção(ões)")
            }
        }
        return chosen.map { opt ->
            OrderItemOption(
                orderItemId = PLACEHOLDER,
                optionId = opt.id!!,
                groupName = groupsById[opt.groupId]!!.name,
                optionName = opt.name,
                priceCents = opt.priceCents,
            )
        }
    }

    @Transactional("tenantTransactionManager")
    fun updateStatus(id: UUID, req: OrderStatusUpdateRequest): OrderResponse {
        val order = orderRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Order not found: $id") }
        validateTransition(order.status, req.status)
        order.status = req.status
        when (req.status) {
            OrderStatus.DELIVERED -> order.completedAt = Instant.now()
            OrderStatus.CANCELLED -> {
                order.cancelledAt = Instant.now()
                order.cancelledReason = req.reason ?: "Cancelled"
            }
            else -> {}
        }
        val saved = orderRepository.save(order)
        // Broadcast to the KDS for THIS tenant (slug from the signed token via
        // TenantContext — authoritative, not a client header). Items are touched
        // here while still inside the tx so the LAZY collection is initialized.
        saved.items.size
        realtimePublisher.publishKds(TenantContext.getOrThrow(), saved)
        return OrderResponse.from(saved)
    }

    /**
     * Active kitchen orders for the KDS screen: PENDING + PREPARING, oldest first.
     * Items are eagerly touched so the response/event carry line details.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun kdsActiveOrders(): List<com.menuflow.dto.KdsOrderView> =
        orderRepository
            .findByStatusInOrderByCreatedAtAsc(listOf(OrderStatus.PENDING, OrderStatus.PREPARING))
            .map { it.items.size; com.menuflow.dto.KdsOrderView.from(it) }

    /**
     * Active PDV orders of the day: PENDING + PREPARING + READY since the start of
     * "today" in São Paulo (business day), oldest first.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun pdvActiveOrders(): List<OrderResponse> {
        val from = LocalDate.now(saoPaulo).atStartOfDay(saoPaulo).toInstant()
        return orderRepository
            .findByStatusInAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                listOf(OrderStatus.PENDING, OrderStatus.PREPARING, OrderStatus.READY),
                from,
            )
            .map { it.items.size; OrderResponse.from(it) }
    }

    /** PENDING -> PREPARING -> READY -> DELIVERED; CANCELLED from any non-terminal state. */
    private fun validateTransition(current: OrderStatus, next: OrderStatus) {
        val allowed = when (current) {
            OrderStatus.PENDING -> setOf(OrderStatus.PREPARING, OrderStatus.CANCELLED)
            OrderStatus.PREPARING -> setOf(OrderStatus.READY, OrderStatus.CANCELLED)
            OrderStatus.READY -> setOf(OrderStatus.DELIVERED, OrderStatus.CANCELLED)
            OrderStatus.DELIVERED -> emptySet()
            OrderStatus.CANCELLED -> emptySet()
        }
        if (next !in allowed) {
            throw BusinessException("Invalid status transition from $current to $next")
        }
    }

    private fun generateOrderNumber(): String {
        val datePart = LocalDate.now(saoPaulo).format(dateFmt)
        val suffix = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
        return "MF-$datePart-$suffix"
    }

    companion object {
        private val PLACEHOLDER: UUID = UUID(0, 0)
    }
}

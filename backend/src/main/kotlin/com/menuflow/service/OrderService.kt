package com.menuflow.service

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.OrderResponse
import com.menuflow.dto.OrderStatusUpdateRequest
import com.menuflow.dto.QuoteItemResponse
import com.menuflow.dto.QuoteRequest
import com.menuflow.dto.QuoteResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.exception.UnprocessableEntityException
import com.menuflow.model.CrustType
import com.menuflow.model.DoughType
import com.menuflow.model.Order
import com.menuflow.model.OrderItem
import com.menuflow.model.OrderItemOption
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.model.Product
import com.menuflow.repository.tenant.IngredientRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.ProductCrustPriceRepository
import com.menuflow.repository.tenant.ProductFlavorRepository
import com.menuflow.repository.tenant.ProductIngredientRepository
import com.menuflow.repository.tenant.ProductOptionGroupRepository
import com.menuflow.repository.tenant.ProductOptionRepository
import com.menuflow.repository.tenant.ProductRepository
import com.menuflow.repository.tenant.ProductSizeRepository
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
    private val productSizeRepository: ProductSizeRepository,
    private val productFlavorRepository: ProductFlavorRepository,
    private val productCrustPriceRepository: ProductCrustPriceRepository,
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
        // 1. Resolve products, build line items (price snapshot), validar complementos
        // e os valores monetários. MESMA lógica que o quote usa -> os totais batem.
        val priced = priceItems(req.items, req.discountCents, req.deliveryFeeCents)
        val items = priced.items
        val optionsByIndex = priced.optionsByIndex
        val subtotal = priced.subtotalCents

        // Ficha técnica: agrega o consumo de insumos a partir das linhas já precificadas.
        val required = HashMap<UUID, Double>()
        items.forEach { item ->
            productIngredientRepository.findByProductId(item.productId).forEach { pi ->
                required.merge(pi.ingredientId, pi.quantity * item.quantity, Double::plus)
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

        // 3. Compute totals (centavos) and persist the order. MESMO cálculo do quote.
        val (deliveryFee, total) = computeTotals(subtotal, req.orderType, req.discountCents, req.deliveryFeeCents)

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
     * Calcula o total de um carrinho SEM criar o pedido (nada é persistido, nenhuma
     * baixa de estoque). Usa EXATAMENTE a mesma lógica de preço do create
     * (priceItems + computeTotals), garantindo que o valor cotado é o que o pedido
     * cobraria. Validações de complemento (obrigatório/min-max/pertencimento) e de
     * desconto/taxa são as mesmas — um carrinho inválido falha aqui como no create.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun quote(req: QuoteRequest): QuoteResponse {
        val priced = priceItems(req.items, req.discountCents, req.deliveryFeeCents)
        val (deliveryFee, total) =
            computeTotals(priced.subtotalCents, req.orderType, req.discountCents, req.deliveryFeeCents)
        val items = priced.items.map { item ->
            QuoteItemResponse.from(item, priced.optionsByIndex[item.displayOrder].orEmpty())
        }
        return QuoteResponse(
            items = items,
            subtotalCents = priced.subtotalCents,
            discountCents = req.discountCents,
            deliveryFeeCents = deliveryFee,
            totalCents = total,
        )
    }

    /**
     * Núcleo de precificação compartilhado por create e quote: resolve produtos,
     * monta as linhas com snapshot de preço (variação de pizza + complementos),
     * acumula o subtotal e valida os valores monetários do carrinho. NÃO toca em
     * estoque nem persiste nada — é cálculo puro em memória.
     */
    private fun priceItems(
        lines: List<OrderItemRequest>,
        discountCents: Long,
        deliveryFeeCents: Long,
    ): PricedCart {
        if (lines.isEmpty()) throw BusinessException("Order must have at least one item")

        val items = mutableListOf<OrderItem>()
        // Snapshots de complementos por linha (index); anexados após o item ter id (no create).
        val optionsByIndex = HashMap<Int, List<OrderItemOption>>()
        var subtotal = 0L

        lines.forEachIndexed { index, line ->
            val product = productRepository.findByIdAndActiveTrue(line.productId)
                ?: throw ResourceNotFoundException("Product not found: ${line.productId}")

            // Complementos: valida pertinência ao produto + regras min/max e faz o
            // snapshot (nome+preço); o adicional entra no preço unitário do item.
            val snapshots = resolveOptions(product.id!!, line.optionIds)
            // Variação de pizza: base (tamanho ou produto) + média dos sabores + borda.
            // Valida que tamanho/sabor pertencem ao produto (anti-IDOR) e gera o snapshot.
            val variation = resolveVariation(product, line)
            val unitPrice = variation.basePriceCents + snapshots.sumOf { it.priceCents }
            val lineTotal = unitPrice * line.quantity
            subtotal += lineTotal
            items.add(
                OrderItem(
                    orderId = PLACEHOLDER, // set after order is persisted (create only)
                    productId = product.id!!,
                    productSku = product.sku,
                    productName = product.name,
                    quantity = line.quantity,
                    unitPriceCents = unitPrice,
                    totalPriceCents = lineTotal,
                    notes = line.notes,
                    displayOrder = index,
                    sizeId = variation.sizeId,
                    sizeName = variation.sizeName,
                    flavor1Id = variation.flavor1Id,
                    flavor1Name = variation.flavor1Name,
                    flavor2Id = variation.flavor2Id,
                    flavor2Name = variation.flavor2Name,
                    crustType = variation.crustType,
                    doughType = variation.doughType,
                ),
            )
            if (snapshots.isNotEmpty()) optionsByIndex[index] = snapshots
        }

        // Valores monetários vindos do cliente: barra sinal negativo e desconto
        // acima do subtotal (senão um operador poderia zerar/reduzir o total via API).
        if (discountCents < 0) throw BusinessException("Desconto não pode ser negativo")
        if (deliveryFeeCents < 0) throw BusinessException("Taxa de entrega não pode ser negativa")
        if (discountCents > subtotal) throw BusinessException("Desconto não pode exceder o subtotal")

        return PricedCart(items, optionsByIndex, subtotal)
    }

    /**
     * Calcula taxa de entrega efetiva e total (centavos) a partir do subtotal.
     * Taxa só se aplica a DELIVERY; total nunca fica negativo. Compartilhado por
     * create e quote para garantir o MESMO valor.
     */
    private fun computeTotals(
        subtotal: Long,
        orderType: OrderType,
        discountCents: Long,
        deliveryFeeCents: Long,
    ): Pair<Long, Long> {
        val deliveryFee = if (orderType == OrderType.DELIVERY) deliveryFeeCents else 0L
        val total = (subtotal - discountCents + deliveryFee).coerceAtLeast(0)
        return deliveryFee to total
    }

    private data class PricedCart(
        val items: List<OrderItem>,
        val optionsByIndex: Map<Int, List<OrderItemOption>>,
        val subtotalCents: Long,
    )

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

    /**
     * Snapshot de preço + variação de pizza de uma linha de pedido.
     *
     * Preço base = preço do TAMANHO (ProductSize) quando `sizeId` é informado,
     * senão `product.priceCents`. Soma a MÉDIA dos preços dos sabores escolhidos
     * (1 sabor = ele mesmo; 2 sabores meia/meia = média dos dois) e o preço da
     * BORDA (CrustType) registrado para o produto (borda sem registro = 0). A
     * MASSA (DoughType) é sem custo nesta fase. Tamanho e sabor são validados
     * como pertencentes ao produto (anti-IDOR, como em resolveOptions).
     */
    private fun resolveVariation(product: Product, line: com.menuflow.dto.OrderItemRequest): Variation {
        val productId = product.id!!

        // Base: tamanho (se informado) ou preço do produto.
        var sizeId: UUID? = null
        var sizeName: String? = null
        val base: Long = if (line.sizeId != null) {
            val size = productSizeRepository.findById(line.sizeId)
                .orElseThrow { BusinessException("Tamanho inválido") }
            if (size.productId != productId) throw BusinessException("Tamanho não pertence a este produto")
            if (!size.active) throw BusinessException("Tamanho indisponível")
            sizeId = size.id; sizeName = size.name
            size.effectivePriceCents(product.isOnPromo())
        } else {
            product.effectivePriceCents()
        }

        // Sabores: resolve, valida pertencimento e coleta os preços para a média.
        val flavorPrices = mutableListOf<Long>()
        var flavor1Id: UUID? = null; var flavor1Name: String? = null
        var flavor2Id: UUID? = null; var flavor2Name: String? = null
        line.flavor1Id?.let {
            val f = resolveFlavor(productId, it)
            flavor1Id = f.id; flavor1Name = f.name; flavorPrices.add(f.priceCents)
        }
        line.flavor2Id?.let {
            val f = resolveFlavor(productId, it)
            flavor2Id = f.id; flavor2Name = f.name; flavorPrices.add(f.priceCents)
        }
        val flavorAvg = averageHalfUp(flavorPrices)

        // Borda: preço do produto para a borda (ausente = 0). Massa: sem custo.
        var crustType: CrustType? = null
        var crustPrice = 0L
        line.crustType?.let { raw ->
            val crust = parseEnum<CrustType>(raw) ?: throw BusinessException("Borda inválida: $raw")
            crustType = crust
            crustPrice = productCrustPriceRepository.findByProductIdAndCrustType(productId, crust)?.priceCents ?: 0L
        }
        val doughType: DoughType? = line.doughType?.let {
            parseEnum<DoughType>(it) ?: throw BusinessException("Massa inválida: $it")
        }

        return Variation(
            basePriceCents = base + flavorAvg + crustPrice,
            sizeId = sizeId, sizeName = sizeName,
            flavor1Id = flavor1Id, flavor1Name = flavor1Name,
            flavor2Id = flavor2Id, flavor2Name = flavor2Name,
            crustType = crustType, doughType = doughType,
        )
    }

    private fun resolveFlavor(productId: UUID, flavorId: UUID): com.menuflow.model.ProductFlavor {
        val f = productFlavorRepository.findById(flavorId)
            .orElseThrow { BusinessException("Sabor inválido") }
        if (f.productId != productId) throw BusinessException("Sabor não pertence a este produto")
        if (!f.active) throw BusinessException("Sabor indisponível")
        return f
    }

    /**
     * Média de preços em centavos com arredondamento HALF-UP (metade arredonda
     * para cima), em aritmética inteira (sem float). Aplicado UMA vez, na borda
     * do cálculo do preço unitário, de forma que o total feche de forma
     * determinística. Lista vazia (sem sabor) = 0.
     */
    private fun averageHalfUp(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val n = values.size.toLong()
        return (values.sum() + n / 2) / n
    }

    private inline fun <reified E : Enum<E>> parseEnum(raw: String): E? =
        enumValues<E>().firstOrNull { it.name == raw }

    private data class Variation(
        val basePriceCents: Long,
        val sizeId: UUID?, val sizeName: String?,
        val flavor1Id: UUID?, val flavor1Name: String?,
        val flavor2Id: UUID?, val flavor2Name: String?,
        val crustType: CrustType?, val doughType: DoughType?,
    )

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
     * Active kitchen orders for the KDS board (3 columns): PENDING + PREPARING shown
     * regardless of age, plus READY only from the start of "today" in São Paulo so the
     * "Prontos" column is not polluted with previous days' orders. Oldest first.
     * Items are eagerly touched so the response carries line details.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun kdsActiveOrders(): List<com.menuflow.dto.KdsOrderView> {
        val readyFrom = LocalDate.now(saoPaulo).atStartOfDay(saoPaulo).toInstant()
        return orderRepository
            .findKdsBoardOrders(readyFrom)
            .map { it.items.size; com.menuflow.dto.KdsOrderView.from(it) }
    }

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

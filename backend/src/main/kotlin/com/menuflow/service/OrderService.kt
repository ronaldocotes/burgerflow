package com.menuflow.service

import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.OrderResponse
import com.menuflow.dto.OrderStatusUpdateRequest
import com.menuflow.dto.QuoteItemResponse
import com.menuflow.dto.QuoteRequest
import com.menuflow.dto.QuoteResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.exception.UnprocessableEntityException
import com.menuflow.event.OrderPaidEvent
import com.menuflow.model.CashSessionStatus
import com.menuflow.model.Customer
import com.menuflow.model.CrustType
import com.menuflow.model.DoughType
import com.menuflow.model.Order
import com.menuflow.model.OrderItem
import com.menuflow.model.OrderItemOption
import com.menuflow.model.OrderStatus
import com.menuflow.model.OrderType
import com.menuflow.model.PaymentMethod
import com.menuflow.model.Product
import com.menuflow.model.SalesChannel
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.CashSessionRepository
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.IngredientRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.ProductCrustPriceRepository
import com.menuflow.repository.tenant.ProductFlavorRepository
import com.menuflow.repository.tenant.ProductIngredientRepository
import com.menuflow.repository.tenant.ProductOptionGroupRepository
import com.menuflow.repository.tenant.ProductOptionRepository
import com.menuflow.repository.tenant.ProductRepository
import com.menuflow.repository.tenant.ProductSizeRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.tenant.TenantContext
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.math.RoundingMode
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
    private val tenantConfigRepository: TenantConfigRepository,
    private val productRecipeService: ProductRecipeService,
    private val cashSessionRepository: CashSessionRepository,
    private val realtimePublisher: com.menuflow.service.RealtimePublisher,
    private val auditLogService: AuditLogService,
    private val couponService: CouponService,
    private val cancellationReasonRepository: com.menuflow.repository.tenant.CancellationReasonRepository,
    private val customerRepository: CustomerRepository,
    private val cartRecoveryService: CartRecoveryService,
    private val trackingService: TrackingService,
    private val eventPublisher: org.springframework.context.ApplicationEventPublisher,
    // Fase A2: taxa de entrega server-side (anti-fraude) por distancia + geocode.
    private val distanceService: com.menuflow.dispatch.DistanceService,
    private val ridePricingService: com.menuflow.dispatch.RidePricingService,
    private val geocodingService: com.menuflow.dispatch.GeocodingService,
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
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
        // Cupom (Fase 3.2) SOBRESCREVE o desconto manual: com um código informado, o
        // desconto do operador é ignorado (anti-fraude — não combina cupom + manual).
        // Sem cupom, o desconto manual segue validado dentro do priceItems (>=0 e
        // <= subtotal).
        val couponCode = req.couponCode?.trim()?.takeIf { it.isNotBlank() }
        val priced = priceItems(
            req.items,
            if (couponCode != null) 0L else req.discountCents,
            req.deliveryFeeCents,
        )
        val items = priced.items
        val optionsByIndex = priced.optionsByIndex
        val subtotal = priced.subtotalCents

        // Valida e TRAVA o cupom (lock pessimista na linha) ANTES de baixar estoque:
        // falha rápida sem segurar lock de insumo, e o lock serializa redenções
        // concorrentes (não estoura maxUses em corrida). A redenção é registrada
        // depois que o pedido existe, na MESMA transação -> atômica e race-safe.
        val couponApp = couponCode?.let {
            couponService.validateAndLock(it, subtotal, req.customerPhone)
        }
        val effectiveDiscount = couponApp?.discountCents ?: req.discountCents

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

        // Config do tenant lida UMA vez (aceite automático + alíquotas do DRE + entrega).
        // Ausência de linha de config = defaults seguros (aceite off, alíquotas 0).
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()

        // Fase A2 — coordenadas de entrega (request ou geocode) + taxa SERVER-SIDE.
        // Para DELIVERY geocodificado com restaurante geolocalizado, a taxa vem do
        // cálculo por distância (RidePricing), IGNORANDO deliveryFeeCents do cliente
        // (anti-fraude). Sem geocode, mantém o valor do request (fluxo legado).
        val (deliveryLat, deliveryLng, geocodeSource) = resolveDeliveryGeo(req)
        val resolvedDeliveryFee = resolveDeliveryFee(req, config, deliveryLat, deliveryLng)

        // 3. Compute totals (centavos) and persist the order. MESMO cálculo do quote.
        // Usa o desconto efetivo (cupom quando houver, senão o manual).
        val (deliveryFee, total) = computeTotals(subtotal, req.orderType, effectiveDiscount, resolvedDeliveryFee)

        // Aceite automático: com o flag ligado o pedido nasce em PREPARING e vai
        // direto para a cozinha, sem ação manual no PENDING.
        val autoAccept = config?.autoAcceptOrders ?: false
        val initialStatus = if (autoAccept) OrderStatus.PREPARING else OrderStatus.PENDING

        // DRE (Fase 3.1) — canal de venda + snapshots de custo/taxa no momento da
        // venda (determinístico mesmo que preços/fichas/alíquotas mudem depois).
        // Canal: pedido público (sem operador) é ONLINE; com operador, segue o
        // orderType. Marketplace só incide no canal DELIVERY; cartão só quando a
        // forma de pagamento já é cartão aqui (no PDV ela é definida no pay(),
        // onde a taxa é recalculada — ver PdvService.pay).
        val channel = when {
            userId == null -> SalesChannel.ONLINE
            req.orderType == OrderType.DELIVERY -> SalesChannel.DELIVERY
            req.orderType == OrderType.DINE_IN -> SalesChannel.DINE_IN
            else -> SalesChannel.COUNTER
        }
        val cogs = items.sumOf { productRecipeService.cmv(it.productId).cmvCents * it.quantity }
        val marketplaceFee =
            if (channel == SalesChannel.DELIVERY) pctOfCents(total, config?.marketplaceFeePct ?: BigDecimal.ZERO) else 0L
        val cardFee = cardFeeWithConfig(total, req.paymentMethod, config)

        // Caixa + pagamento no balcão: um operador autenticado (userId != null) que já
        // escolhe a forma de pagamento na criação está registrando uma venda PAGA no
        // balcão — é o fluxo do PDV (web e app Android): POST /orders com paymentMethod,
        // SEM um pay() posterior. Marca o pedido como PAID e o carimba com o turno de
        // caixa aberto, para a venda entrar no caixa (gaveta + reconciliação por forma) e
        // no faturamento. Sem isso a venda ficava PENDING e sumia do caixa/DRE. Regras:
        //  - CASH exige caixa aberto (409 sem turno) e alimenta o esperado da gaveta;
        //  - CREDIT_CARD/DEBIT_CARD/OTHER não exigem caixa, mas são carimbados com o
        //    turno aberto (se houver) para a reconciliação por forma do fechamento
        //    (mesmo carimbo que PdvService.pay aplica a todas as formas);
        //  - PIX fica PENDENTE: a confirmação é assíncrona (webhook Asaas ->
        //    PixPaymentService), que é quem marca PAID;
        //  - pedido do cardápio PÚBLICO (userId == null) NUNCA é marcado pago aqui,
        //    mesmo escolhendo "dinheiro" — é pagamento na entrega/PIX online, fora da
        //    gaveta do balcão;
        //  - o endpoint /pdv (PdvService.createOrder) cria SEM paymentMethod -> cai
        //    fora deste bloco e segue pagando via PdvService.pay (inalterado).
        var cashSessionId: java.util.UUID? = null
        var paymentStatus = com.menuflow.model.PaymentStatus.PENDING
        val payMethod = req.paymentMethod
        if (userId != null && payMethod != null && payMethod != PaymentMethod.PIX) {
            val openSession = cashSessionRepository.findFirstByStatus(CashSessionStatus.OPEN)
            if (payMethod == PaymentMethod.CASH && openSession == null) {
                throw ConflictException("Abra o caixa para registrar vendas em dinheiro")
            }
            cashSessionId = openSession?.id
            paymentStatus = com.menuflow.model.PaymentStatus.PAID
        }

        // Fidelidade (Fase 3.3): se o operador informou telefone e não passou customerId,
        // faz upsert do Customer por telefone para que OrderPaidEvent credite pontos.
        val resolvedCustomerId: java.util.UUID? = req.customerId
            ?: req.customerPhone?.trim()?.takeIf { it.isNotEmpty() }?.let { phone ->
                (customerRepository.findByPhoneNumber(phone)
                    ?: customerRepository.save(Customer(name = phone, phoneNumber = phone))).id
            }

        val order = Order(
            orderNumber = generateOrderNumber(),
            customerId = resolvedCustomerId,
            customerPhone = req.customerPhone?.trim()?.takeIf { it.isNotEmpty() },
            userId = userId,
            orderType = req.orderType,
            status = initialStatus,
            tableNumber = req.tableNumber,
            notes = req.notes,
            subtotalCents = subtotal,
            discountCents = effectiveDiscount,
            deliveryFeeCents = deliveryFee,
            totalCents = total,
            paymentMethod = req.paymentMethod,
            paymentStatus = paymentStatus,
            cashSessionId = cashSessionId,
            couponId = couponApp?.coupon?.id,
            couponCode = couponApp?.coupon?.code,
            couponDiscountCents = couponApp?.discountCents ?: 0,
            salesChannel = channel,
            cogsCents = cogs,
            marketplaceFeeCents = marketplaceFee,
            cardFeeCents = cardFee,
            estimatedPrepTimeMinutes = (req.items.sumOf { it.quantity } * 5).coerceAtLeast(10),
        )
        // Fase A2/B1 — grava o endereco de entrega + geocode no pedido de DELIVERY
        // (o despacho precisa de bairro/coordenadas). Additivo: sem req.delivery os
        // campos ficam null e nada muda no fluxo legado.
        if (req.orderType == OrderType.DELIVERY) {
            req.delivery?.let { addr ->
                order.deliveryRecipientName = addr.recipientName?.trim()?.takeIf { it.isNotEmpty() }
                order.deliveryPhone = addr.phone?.trim()?.takeIf { it.isNotEmpty() }
                order.deliveryCep = addr.cep?.trim()?.takeIf { it.isNotEmpty() }
                order.deliveryStreet = addr.street?.trim()?.takeIf { it.isNotEmpty() }
                order.deliveryNumber = addr.number?.trim()?.takeIf { it.isNotEmpty() }
                order.deliveryComplement = addr.complement?.trim()?.takeIf { it.isNotEmpty() }
                order.deliveryNeighborhood = addr.neighborhood?.trim()?.takeIf { it.isNotEmpty() }
                order.deliveryCity = addr.city?.trim()?.takeIf { it.isNotEmpty() }
                order.deliveryReference = addr.reference?.trim()?.takeIf { it.isNotEmpty() }
            }
            order.deliveryLat = deliveryLat
            order.deliveryLng = deliveryLng
            order.deliveryGeocodeSource = geocodeSource
        }
        val saved = orderRepository.save(order)
        items.forEach { it.orderId = saved.id!! }
        saved.items.addAll(items)
        val persisted = orderRepository.save(saved)
        // Cupom: registra a redenção agora que o pedido tem id, na MESMA transação
        // (o lock pessimista da validação ainda está ativo -> race-safe).
        couponApp?.let {
            couponService.recordRedemption(it.coupon, persisted.id!!, req.customerPhone, it.discountCents)
        }
        // Desconto MANUAL aplicado por um operador autenticado é um ato sensível (afeta
        // o total cobrado) -> auditar. Com cupom, o registro é a própria redenção
        // (coupon_redemptions). Pedido público (sem principal) não registra: o
        // AuditLogService pula quando não há ator resolvível.
        if (couponCode == null && req.discountCents > 0) {
            auditLogService.log(
                action = "order.discount",
                entity = "order",
                entityId = persisted.id,
                after = mapOf("discountCents" to req.discountCents),
            )
        }
        // Recuperacao de carrinho abandonado (Fase 3.5): se o pedido nasceu pendente de
        // pagamento e tem telefone, cria a comanda de recuperacao. A insercao roda APOS
        // o commit (FK em orders(id) + nunca pode derrubar o pedido) — o service trata.
        cartRecoveryService.onOrderCreated(persisted)
        // Tracking first-party (Fase 3.6): se o pedido veio de um link rastreavel,
        // registra a conversao (receita = total do pedido). O servico trata: insere
        // APOS o commit (FK em orders(id)) em tx propria, idempotente e fail-safe —
        // nunca derruba a criacao do pedido.
        req.trackingLinkId?.let { trackingService.recordConversion(persisted.id!!, it, persisted.totalCents) }
        // Venda paga no balcão (operador escolheu a forma na criação): publica o fato de
        // domínio OrderPaidEvent — os MESMOS listeners AFTER_COMMIT de PdvService.pay
        // (fidelidade credita pontos, carrinho abandonado -> RECOVERED etc.) reagem, agora
        // também para as vendas do PDV que nascem pagas aqui. O slug do tenant vem do
        // TenantContext (token assinado) para rotear de volta no db-per-tenant. PIX e
        // pedido público não chegam aqui (ficam PENDING) -> sem evento duplicado; o fluxo
        // /pdv publica o evento no próprio pay().
        if (paymentStatus == com.menuflow.model.PaymentStatus.PAID) {
            eventPublisher.publishEvent(
                OrderPaidEvent(
                    tenantSlug = TenantContext.getOrThrow(),
                    orderId = persisted.id!!,
                    customerId = persisted.customerId,
                    customerPhone = persisted.customerPhone,
                    totalCents = persisted.totalCents,
                ),
            )
        }
        // KDS ao vivo: o pedido NOVO também precisa aparecer na cozinha (< 1s),
        // não só as mudanças de status (updateStatus). Publica APÓS o commit —
        // ver publishKdsAfterCommit para o porquê. Vale para PENDING e para o
        // pedido que nasce PREPARING via aceite automático (ambos são colunas
        // do board).
        persisted.items.size // materializa a coleção LAZY ainda dentro da tx
        publishKdsAfterCommit(TenantContext.getOrThrow(), persisted)
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
     * Publica o evento de KDS da criação do pedido APÓS o commit, fail-open.
     *
     * Por que AFTER_COMMIT (e não inline como o updateStatus faz): o cliente do
     * KDS pode reagir ao evento refazendo o GET /kds/orders (é o que o app
     * mobile faz para pedido desconhecido); se o evento saísse ainda dentro da
     * transação, o refetch poderia rodar ANTES do commit e não enxergar o
     * pedido. Fail-open: falha no broker NUNCA derruba a criação do pedido —
     * o board se reconcilia pelo polling/snapshot.
     *
     * Sem transação ativa (ex.: teste service-level), publica imediatamente —
     * mesmo padrão do CartRecoveryService.onOrderCreated.
     */
    private fun publishKdsAfterCommit(tenantSlug: String, order: Order) {
        val publish = {
            try {
                realtimePublisher.publishKds(tenantSlug, order)
            } catch (e: Exception) {
                log.error(
                    "Falha ao publicar evento KDS do pedido {} (fail-open): {}",
                    order.orderNumber, e.message,
                )
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = publish()
                },
            )
        } else {
            publish()
        }
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

    /**
     * Fase A2 — resolve as coordenadas de entrega de um pedido DELIVERY. Usa lat/lng
     * do request quando presentes (origem MANUAL); senão tenta geocodar o endereço
     * (Google → origem GOOGLE). Retorna (lat?, lng?, source?) — tudo null se não é
     * DELIVERY, não há endereço, ou o geocode falhou (fail-safe: nunca lança).
     */
    private fun resolveDeliveryGeo(req: OrderCreateRequest): Triple<Double?, Double?, String?> {
        if (req.orderType != OrderType.DELIVERY) return Triple(null, null, null)
        val addr = req.delivery ?: return Triple(null, null, null)
        if (addr.lat != null && addr.lng != null) return Triple(addr.lat, addr.lng, "MANUAL")
        val geo = geocodingService.geocode(addr.street, addr.neighborhood, addr.city, addr.cep)
        return if (geo != null) Triple(geo.lat, geo.lng, "GOOGLE") else Triple(null, null, null)
    }

    /**
     * Fase A2 (anti-fraude) — taxa de entrega SERVER-SIDE. Para DELIVERY com geocode
     * do destino E restaurante geolocalizado, IGNORA req.deliveryFeeCents e calcula a
     * tarifa pela distância rodoviária (RidePricing). Sem geocode (endereço não
     * resolvido ou restaurante sem coordenadas), mantém o valor do request para não
     * regredir o fluxo legado em que o operador define a taxa manualmente.
     */
    private fun resolveDeliveryFee(
        req: OrderCreateRequest,
        config: TenantConfig?,
        destLat: Double?,
        destLng: Double?,
    ): Long {
        if (req.orderType != OrderType.DELIVERY) return req.deliveryFeeCents
        val originLat = config?.restaurantLat
        val originLng = config?.restaurantLng
        if (originLat == null || originLng == null || destLat == null || destLng == null) {
            return req.deliveryFeeCents
        }
        val meters = distanceService.getRoadDistanceMeters(
            originLat, originLng, destLat, destLng, config.distanceProvider,
        )
        return ridePricingService.feeCents(config, meters)
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
        val previousStatus = order.status
        order.status = req.status
        when (req.status) {
            OrderStatus.DELIVERED -> order.completedAt = Instant.now()
            OrderStatus.CANCELLED -> {
                order.cancelledAt = Instant.now()
                // Motivo pre-cadastrado (issue #10): se um id valido veio, o texto do
                // catalogo prevalece e fica denormalizado no pedido (sobrevive a
                // edicao/exclusao do motivo). Id inexistente -> 400. Sem id -> texto livre.
                val reasonId = req.cancelledReasonId
                if (reasonId != null) {
                    val reason = cancellationReasonRepository.findById(reasonId)
                        .orElseThrow { IllegalArgumentException("Motivo de cancelamento nao encontrado: $reasonId") }
                    order.cancelledReasonId = reason.id
                    order.cancelledReason = reason.description
                } else {
                    order.cancelledReason = req.reason ?: "Cancelled"
                }
            }
            else -> {}
        }
        val saved = orderRepository.save(order)
        // Toda transição de status válida é auditada — não só o cancelamento (Centurião,
        // 2026-07-08): avançar em lote (PENDING→PREPARING→READY→DELIVERED) hoje só
        // sobrescrevia updatedAt, sem trilha de status anterior nem autoria por pedido.
        // Cancelamento mantém a action específica "order.cancel" (já existente, com o
        // motivo em `reason`); demais avanços usam "order.status_change" com o status
        // novo em `after`. Ator vem do JWT/principal autenticado dentro de
        // auditLogService.log (SecurityUtils.currentPrincipal) — não é passado aqui, logo
        // não é forjável pelo corpo da requisição.
        if (req.status == OrderStatus.CANCELLED) {
            auditLogService.log(
                action = "order.cancel",
                entity = "order",
                entityId = saved.id,
                before = mapOf("status" to previousStatus.name),
                reason = order.cancelledReason,
            )
        } else {
            auditLogService.log(
                action = "order.status_change",
                entity = "order",
                entityId = saved.id,
                before = mapOf("status" to previousStatus.name),
                after = mapOf("status" to req.status.name),
            )
        }
        // Broadcast to the KDS for THIS tenant (slug from the signed token via
        // TenantContext — authoritative, not a client header). Items are touched
        // here while still inside the tx so the LAZY collection is initialized.
        saved.items.size
        realtimePublisher.publishKds(TenantContext.getOrThrow(), saved)
        // Notificacao WhatsApp ao cliente (Fase 2.4): publica um fato de dominio que
        // o WhatsAppService consome APOS o commit (AFTER_COMMIT) — fora desta tx, sem
        // segurar a conexao do banco e sem disparar se houver rollback. So os marcos
        // PREPARING/READY/DELIVERED notificam (kindFor); demais sao silenciosos.
        WhatsAppService.kindFor(saved.status)?.let { kind ->
            val restaurantName = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
                ?.restaurantName ?: "o restaurante"
            eventPublisher.publishEvent(
                OrderStatusNotification(saved.customerPhone, kind, restaurantName),
            )
        }
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

    // --- DRE Automático (Fase 3.1): cálculo de taxa de cartão ---

    /**
     * Taxa de cartão (centavos) sobre o total quando a forma de pagamento é cartão
     * (CREDIT_CARD/DEBIT_CARD); senão 0. Lê a alíquota da config do tenant. Público
     * porque o PDV define a forma de pagamento só no pay() (PdvService) — é lá que
     * a taxa é (re)calculada, depois do carimbo da forma de pagamento.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun computeCardFeeCents(totalCents: Long, method: PaymentMethod?): Long =
        cardFeeWithConfig(totalCents, method, tenantConfigRepository.findFirstByOrderByCreatedAtAsc())

    /** Igual ao acima, mas reusando uma config já carregada (evita 2ª leitura no create). */
    private fun cardFeeWithConfig(totalCents: Long, method: PaymentMethod?, config: TenantConfig?): Long {
        if (method != PaymentMethod.CREDIT_CARD && method != PaymentMethod.DEBIT_CARD) return 0L
        return pctOfCents(totalCents, config?.cardFeePct ?: BigDecimal.ZERO)
    }

    /** Aplica uma alíquota (%) sobre centavos, HALF-UP, em BigDecimal (sem float). */
    private fun pctOfCents(cents: Long, pct: BigDecimal): Long =
        BigDecimal.valueOf(cents).multiply(pct).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()

    companion object {
        private val PLACEHOLDER: UUID = UUID(0, 0)
    }
}

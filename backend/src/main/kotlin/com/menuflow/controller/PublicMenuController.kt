package com.menuflow.controller

import com.menuflow.dto.ApplyCouponRequest
import com.menuflow.dto.ApplyCouponResponse
import com.menuflow.dto.CategoryResponse
import com.menuflow.dto.DeliveryAddressRequest
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.PublicProductResponse
import com.menuflow.dto.TrackingRedirectResponse
import com.menuflow.exception.BusinessException
import com.menuflow.model.OrderType
import com.menuflow.model.PaymentMethod
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.OrderItemRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.CategoryService
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.service.TrackingService
import com.menuflow.tenant.TenantContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Dados de marca/vitrine do restaurante para o cabecalho do cardapio publico. */
data class RestaurantInfo(
    val name: String?,
    val logoUrl: String?,
    val coverUrl: String?,
    val address: String?,
    val openingHours: String?,
    // Horario por dia da semana (issue #6), formato "HH:mm-HH:mm"; null = fechado.
    val hoursByWeekday: Map<String, String?>,
    /** Status aberto/fechado calculado no servidor (America/Sao_Paulo) a partir dos
     * horarios por dia. null quando o dia atual nao tem horario configurado. */
    val openNow: Boolean?,
)

/** Promessa de prazo (min-max, minutos) por modalidade (issue #9). */
data class PublicDeliveryTimes(
    val deliveryMinMinutes: Int,
    val deliveryMaxMinutes: Int,
    val pickupMinMinutes: Int,
    val pickupMaxMinutes: Int,
    val dineinMinMinutes: Int,
    val dineinMaxMinutes: Int,
)

/**
 * Tema do cardapio publico (issue #12). recommendedTextColor vem calculado no
 * servidor (WCAG) para o frontend pintar o texto legivel sobre a cor de marca;
 * null quando nao ha cor configurada (frontend usa o default). Os toggles dizem
 * o que renderizar (preco/descricao/foto).
 */
data class PublicThemeResponse(
    val primaryColor: String?,
    val recommendedTextColor: String?,
    val showPrices: Boolean,
    val showDescriptions: Boolean,
    val showPhotos: Boolean,
)

data class PublicMenuResponse(
    val categories: List<CategoryResponse>,
    val products: List<PublicProductResponse>,
    /** Chave PIX estatica do restaurante; null quando nao configurada. */
    val pixKey: String?,
    /** Marca/vitrine do restaurante (nome, logo, capa, endereco, horario). */
    val restaurantInfo: RestaurantInfo,
    /** Promessa de prazo por modalidade (issue #9). */
    val deliveryTimes: PublicDeliveryTimes,
    /** Ids dos produtos mais vendidos (so UUIDs; sem contagem nem receita). */
    val bestsellerIds: List<UUID>,
    /** Tema do cardapio: cor de marca + toggles (issue #12). */
    val theme: PublicThemeResponse,
    /** Pop-up de entrada com produtos em destaque (issue #13). */
    val entryPopup: com.menuflow.dto.PublicEntryPopupResponse,
)

data class PublicOrderItemRequest(
    val productId: UUID,
    @field:Positive @field:Max(99) val quantity: Int = 1,
    val notes: String? = null,
    /** Complementos escolhidos (ids de option groups); validados em OrderService.resolveOptions. */
    val optionIds: List<UUID> = emptyList(),
)

/**
 * Endereco de entrega enviado pelo cardapio publico (DELIVERY). SEM lat/lng: no MVP
 * nao ha pin de mapa — o servidor geocoda (GeocodingService) a partir do texto do
 * endereco e resolve o frete por zona. Campos com tamanhos sanos; validado/geocodado
 * em OrderService.quoteDelivery.
 */
data class PublicDeliveryRequest(
    @field:Size(max = 200) val street: String? = null,
    @field:Size(max = 20) val number: String? = null,
    @field:Size(max = 100) val complement: String? = null,
    @field:Size(max = 100) val neighborhood: String? = null,
    @field:Size(max = 100) val city: String? = null,
    @field:Size(max = 9) val cep: String? = null,
    @field:Size(max = 200) val reference: String? = null,
)

data class PublicOrderRequest(
    @field:NotBlank val customerName: String,
    @field:NotBlank val paymentMethod: String,
    val tableLabel: String? = null,
    @field:NotEmpty @field:Size(max = 20) val items: List<PublicOrderItemRequest>,
    val observations: String? = null,
    /** Telefone p/ receber avisos do pedido por WhatsApp (Fase 2.4); opt-in, opcional. */
    @field:Size(max = 20) val customerPhone: String? = null,
    /** Cupom de desconto opcional (Fase 3.2); validado/redimido em OrderService.create. */
    @field:Size(max = 50) val couponCode: String? = null,
    /**
     * Modalidade do pedido: "DELIVERY" / "PICKUP" (=TAKEAWAY) / "DINE_IN". null ou vazio
     * => DINE_IN (compat com o front antigo, que so fazia pedido de mesa). Valor invalido
     * => 400. O publico NUNCA envia taxa de entrega: o frete e sempre server-side.
     */
    @field:Size(max = 20) val orderType: String? = null,
    /** Endereco de entrega; obrigatorio quando orderType=DELIVERY (senao 400). */
    @field:Valid val delivery: PublicDeliveryRequest? = null,
)

data class PublicOrderCreatedResponse(
    val orderId: UUID,
    val orderNumber: String,
    val totalCents: Long,
    /** Frete resolvido server-side (0 quando nao e DELIVERY ou saiu gratis). */
    val deliveryFeeCents: Long,
    /** Janela de prazo (minutos); preenchida em DELIVERY, null nas demais modalidades. */
    val etaMinMinutes: Int? = null,
    val etaMaxMinutes: Int? = null,
)

/** Corpo da cotacao publica de frete (POST /public/{slug}/delivery-quote). */
data class PublicDeliveryQuoteRequest(
    @field:Size(max = 200) val street: String? = null,
    @field:Size(max = 100) val neighborhood: String? = null,
    @field:Size(max = 100) val city: String? = null,
    @field:Size(max = 9) val cep: String? = null,
    /**
     * Subtotal atual do carrinho (centavos), opcional: quando informado reflete o
     * frete gratis por valor minimo do pedido. Ausente => 0 (frete cheio na previa).
     */
    val subtotalCents: Long? = null,
)

/** Resposta da cotacao publica de frete: taxa, janela de ETA e se saiu gratis. */
data class PublicDeliveryQuoteResponse(
    val feeCents: Long,
    val etaMinMinutes: Int,
    val etaMaxMinutes: Int,
    val free: Boolean,
)

/**
 * Configuração do programa de fidelidade exposta publicamente para o cardápio.
 * O frontend usa esses dados para calcular/exibir a estimativa de pontos ao cliente
 * antes de finalizar o pedido — sem expor dados de gestão.
 */
data class PublicLoyaltyConfigResponse(
    val enabled: Boolean,
    val pointsPerReal: Int,
    val rewardThreshold: Int,
    val rewardDescription: String?,
)

@RestController
@RequestMapping("/public")
class PublicMenuController(
    private val tenantRepository: TenantRepository,
    private val categoryService: CategoryService,
    private val productService: ProductService,
    private val orderService: OrderService,
    private val couponService: com.menuflow.service.CouponService,
    private val campaignService: com.menuflow.service.CampaignService,
    private val tenantConfigRepository: TenantConfigRepository,
    private val orderItemRepository: OrderItemRepository,
    private val trackingService: TrackingService,
    private val menuLinkService: com.menuflow.service.MenuLinkService,
    private val entryPopupService: com.menuflow.service.EntryPopupService,
) {
    @GetMapping("/{tenantSlug}/menu")
    fun getMenu(@PathVariable tenantSlug: String): ResponseEntity<PublicMenuResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            val categories = categoryService.list(Pageable.ofSize(100)).content
            val products = productService.listPublic(Pageable.ofSize(500)).content
            // Dentro do TenantContext: as queries roteiam para o banco do tenant.
            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
            val pixKey = config?.pixKey
            val hoursByWeekday = weekdayHours(config)
            val restaurantInfo = RestaurantInfo(
                name = config?.restaurantName,
                logoUrl = config?.logoUrl,
                coverUrl = config?.coverUrl,
                address = config?.address,
                openingHours = config?.openingHours,
                hoursByWeekday = hoursByWeekday,
                openNow = computeOpenNow(hoursByWeekday),
            )
            val deliveryTimes = PublicDeliveryTimes(
                deliveryMinMinutes = config?.deliveryTimeMinMinutes ?: 30,
                deliveryMaxMinutes = config?.deliveryTimeMaxMinutes ?: 60,
                pickupMinMinutes = config?.pickupTimeMinMinutes ?: 15,
                pickupMaxMinutes = config?.pickupTimeMaxMinutes ?: 30,
                dineinMinMinutes = config?.dineinTimeMinMinutes ?: 10,
                dineinMaxMinutes = config?.dineinTimeMaxMinutes ?: 20,
            )
            // So os 5 mais vendidos, apenas ids (nunca contagem/receita no publico).
            val bestsellerIds = orderItemRepository.findTopProductIds(Pageable.ofSize(5))
            // Tema (issue #12): cor de marca + toggles + cor de texto recomendada (WCAG).
            val contrast = com.menuflow.dto.ThemeContrastInfo.of(config?.themePrimaryColor)
            val theme = PublicThemeResponse(
                primaryColor = config?.themePrimaryColor,
                recommendedTextColor = contrast?.recommendedTextColor,
                showPrices = config?.themeShowPrices ?: true,
                showDescriptions = config?.themeShowDescriptions ?: true,
                showPhotos = config?.themeShowPhotos ?: true,
            )
            // Pop-up de entrada (issue #13): so produtos ativos; vazio quando desligado.
            val entryPopup = entryPopupService.getForPublicMenu(config)
            ResponseEntity.ok(
                PublicMenuResponse(
                    categories, products, pixKey, restaurantInfo, deliveryTimes, bestsellerIds, theme, entryPopup,
                ),
            )
        } finally {
            TenantContext.clear()
        }
    }

    @PostMapping("/{tenantSlug}/orders")
    fun placeOrder(
        @PathVariable tenantSlug: String,
        @Valid @RequestBody req: PublicOrderRequest,
    ): ResponseEntity<PublicOrderCreatedResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            // Modalidade: null/vazio -> DINE_IN (compat com o front antigo de mesa).
            // Valor invalido -> 400 (BusinessException). "PICKUP" e sinonimo publico de
            // TAKEAWAY (o enum de dominio nao tem PICKUP).
            val orderType = resolvePublicOrderType(req.orderType)
            val paymentMethod = runCatching { PaymentMethod.valueOf(req.paymentMethod) }.getOrNull()
            val notes = buildString {
                append("Cliente: ${req.customerName}")
                req.tableLabel?.let { append(" | Mesa: $it") }
                req.observations?.takeIf { it.isNotBlank() }?.let { append(" | Obs: $it") }
            }
            val items = req.items.map {
                OrderItemRequest(
                    productId = it.productId,
                    quantity = it.quantity,
                    notes = it.notes,
                    optionIds = it.optionIds,
                )
            }

            // DELIVERY: geocoda o endereco e resolve a zona ANTES de criar (obtem ETA +
            // coords). Falha (sem endereco -> 400; sem geocode / fora de area -> 422) nao
            // cria pedido. As coords resolvidas sao repassadas ao create (delivery.lat/lng)
            // para evitar um SEGUNDO geocode; o create reresolve o frete server-side com o
            // subtotal real (frete gratis por valor minimo). O publico nunca envia fee.
            var quote: com.menuflow.service.DeliveryQuoteView? = null
            var deliveryAddr: DeliveryAddressRequest? = null
            if (orderType == OrderType.DELIVERY) {
                val d = req.delivery ?: throw BusinessException("Endereço de entrega obrigatório")
                quote = orderService.quoteDelivery(
                    DeliveryAddressRequest(
                        cep = d.cep, street = d.street, number = d.number,
                        complement = d.complement, neighborhood = d.neighborhood,
                        city = d.city, reference = d.reference,
                    ),
                    subtotalCents = 0,
                )
                deliveryAddr = DeliveryAddressRequest(
                    recipientName = req.customerName,
                    phone = req.customerPhone,
                    cep = d.cep, street = d.street, number = d.number,
                    complement = d.complement, neighborhood = d.neighborhood,
                    city = d.city, reference = d.reference,
                    // As coords NAO vao no endereco (senao o create as trataria como
                    // origem "MANUAL" = informadas pelo cliente). Vao no override abaixo,
                    // com a origem REAL do geocode server-side (A4).
                )
            }
            // A4/A1: coords ja geocodadas na cotacao -> repassa ao create como override
            // (evita 2o geocode e grava delivery_geocode_source="GOOGLE", nao "MANUAL").
            val geoOverride = quote?.let {
                com.menuflow.service.ResolvedDeliveryGeo(it.lat, it.lng, "GOOGLE")
            }

            val orderReq = OrderCreateRequest(
                orderType = orderType,
                tableNumber = if (orderType == OrderType.DINE_IN) req.tableLabel else null,
                customerPhone = req.customerPhone,
                notes = notes,
                paymentMethod = paymentMethod,
                couponCode = req.couponCode,
                delivery = deliveryAddr,
                items = items,
            )
            val created = orderService.create(orderReq, null, geoOverride)
            ResponseEntity.ok(
                PublicOrderCreatedResponse(
                    orderId = created.id,
                    orderNumber = created.orderNumber,
                    totalCents = created.totalCents,
                    // Frete autoritativo do pedido criado (inclui frete gratis por valor);
                    // ETA vem da zona resolvida na cotacao.
                    deliveryFeeCents = created.deliveryFeeCents,
                    etaMinMinutes = quote?.etaMinMinutes,
                    etaMaxMinutes = quote?.etaMaxMinutes,
                ),
            )
        } finally {
            TenantContext.clear()
        }
    }

    /**
     * Cotacao publica de frete (Fase 1 delivery): o cliente digita o endereco e ve o
     * frete + prazo ANTES de fechar o pedido. Molde do apply-coupon: nao persiste nada.
     * Geocoda server-side e resolve a zona (mesma origem/regra do create). Fora de area /
     * sem geocode / restaurante sem localizacao -> 422. Rate-limited por IP (geocode tem
     * custo externo — ver PublicOrderRateLimitFilter).
     */
    @PostMapping("/{tenantSlug}/delivery-quote")
    fun deliveryQuote(
        @PathVariable tenantSlug: String,
        @Valid @RequestBody req: PublicDeliveryQuoteRequest,
    ): ResponseEntity<PublicDeliveryQuoteResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        // Sem nenhum campo de endereco nao ha o que geocodar -> 400 (antes de tocar o banco).
        if (listOf(req.street, req.neighborhood, req.city, req.cep).all { it.isNullOrBlank() }) {
            throw BusinessException("Endereço de entrega obrigatório")
        }
        TenantContext.set(tenantSlug)
        return try {
            val q = orderService.quoteDelivery(
                DeliveryAddressRequest(
                    street = req.street, neighborhood = req.neighborhood,
                    city = req.city, cep = req.cep,
                ),
                subtotalCents = (req.subtotalCents ?: 0L).coerceAtLeast(0L),
            )
            ResponseEntity.ok(
                PublicDeliveryQuoteResponse(
                    feeCents = q.feeCents,
                    etaMinMinutes = q.etaMinMinutes,
                    etaMaxMinutes = q.etaMaxMinutes,
                    free = q.free,
                ),
            )
        } finally {
            TenantContext.clear()
        }
    }

    /**
     * Traduz a modalidade textual do publico para o enum de dominio. null/vazio ->
     * DINE_IN (compat). "PICKUP" -> TAKEAWAY (sinonimo). Valor desconhecido -> 400.
     */
    private fun resolvePublicOrderType(raw: String?): OrderType {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return OrderType.DINE_IN
        return when (v.uppercase()) {
            "DINE_IN" -> OrderType.DINE_IN
            "DELIVERY" -> OrderType.DELIVERY
            "PICKUP", "TAKEAWAY" -> OrderType.TAKEAWAY
            else -> throw BusinessException("Tipo de pedido inválido: $raw")
        }
    }

    /**
     * Pré-checagem pública de cupom (Fase 3.2): o cliente confere o desconto antes de
     * fechar o pedido. Não persiste nada. Cupom inválido propaga 404/400 com mensagem
     * (GlobalExceptionHandler). Rate-limited por IP (PublicOrderRateLimitFilter).
     */
    @PostMapping("/{tenantSlug}/apply-coupon")
    fun applyCoupon(
        @PathVariable tenantSlug: String,
        @Valid @RequestBody req: ApplyCouponRequest,
    ): ResponseEntity<ApplyCouponResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            val app = couponService.preview(req.code, req.subtotalCents, req.customerPhone)
            ResponseEntity.ok(ApplyCouponResponse(valid = true, discountCents = app.discountCents, description = app.description))
        } finally {
            TenantContext.clear()
        }
    }

    /**
     * Clique em link de tracking first-party (Fase 3.6). O cliente abre
     * https://.../r/{trackingSlug}; o frontend chama este endpoint, que registra o
     * CLICK (IP anonimizado + user-agent) e devolve a URL de destino. NAO redireciona
     * server-side (302) — o Next.js gerencia o redirect. Slug invalido/inativo -> 404.
     * Rate-limited por IP (PublicOrderRateLimitFilter cobre /r/).
     */
    @GetMapping("/{tenantSlug}/r/{trackingSlug}")
    fun trackClick(
        @PathVariable tenantSlug: String,
        @PathVariable trackingSlug: String,
        request: HttpServletRequest,
    ): ResponseEntity<TrackingRedirectResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            val redirect = trackingService.recordClick(trackingSlug, clientIp(request), request.getHeader("User-Agent"))
            ResponseEntity.ok(redirect)
        } finally {
            TenantContext.clear()
        }
    }

    /**
     * Resolucao publica de um link/QR do cardapio (issue #11). O frontend abre
     * /public/{tenant}/l/{linkSlug} e este endpoint diz qual modo renderizar
     * (completo/vitrine/balcao) e se o pedido esta habilitado. Slug invalido/inativo
     * -> 404. Rate-limited por IP (PublicOrderRateLimitFilter cobre /l/).
     */
    @GetMapping("/{tenantSlug}/l/{linkSlug}")
    fun resolveMenuLink(
        @PathVariable tenantSlug: String,
        @PathVariable linkSlug: String,
    ): ResponseEntity<com.menuflow.dto.PublicMenuLinkResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            ResponseEntity.ok(menuLinkService.resolvePublic(linkSlug))
        } finally {
            TenantContext.clear()
        }
    }

    /** Mapa dia-da-semana -> "HH:mm-HH:mm" (ou null = fechado) lido do tenant_config. */
    private fun weekdayHours(c: com.menuflow.model.TenantConfig?): Map<String, String?> = linkedMapOf(
        "MONDAY" to c?.openingHoursMonday,
        "TUESDAY" to c?.openingHoursTuesday,
        "WEDNESDAY" to c?.openingHoursWednesday,
        "THURSDAY" to c?.openingHoursThursday,
        "FRIDAY" to c?.openingHoursFriday,
        "SATURDAY" to c?.openingHoursSaturday,
        "SUNDAY" to c?.openingHoursSunday,
    )

    /**
     * Calcula aberto/fechado AGORA (America/Sao_Paulo) a partir do horario do dia
     * corrente. Formato "HH:mm-HH:mm". Suporta virada de meia-noite (ex.: "18:00-02:00").
     * Retorna null quando o dia atual nao tem horario configurado (indeterminado).
     */
    private fun computeOpenNow(hoursByWeekday: Map<String, String?>): Boolean? {
        val now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"))
        val today: DayOfWeek = now.dayOfWeek
        val raw = hoursByWeekday[today.name]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val parts = raw.split("-")
        if (parts.size != 2) return null
        val open = runCatching { LocalTime.parse(parts[0].trim()) }.getOrNull() ?: return null
        val close = runCatching { LocalTime.parse(parts[1].trim()) }.getOrNull() ?: return null
        val t = now.toLocalTime()
        return if (close > open) {
            // Mesmo dia: [open, close).
            !t.isBefore(open) && t.isBefore(close)
        } else {
            // Vira a meia-noite: aberto de open ate 24h OU de 00h ate close.
            !t.isBefore(open) || t.isBefore(close)
        }
    }

    /** IP do cliente: 1o X-Forwarded-For (atras de proxy/LB) ou remoteAddr. */
    private fun clientIp(request: HttpServletRequest): String? {
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) return xff.split(",").first().trim()
        return request.remoteAddr
    }

    /**
     * Descadastro publico de marketing (Fase 3.4): o cliente clica no link "PARAR" e
     * cai aqui. Idempotente e silencioso (sempre 204, mesmo sem cliente cadastrado —
     * nao vaza existencia). Rate-limited por IP (PublicOrderRateLimitFilter).
     */
    @PostMapping("/{tenantSlug}/whatsapp-opt-out")
    fun optOut(
        @PathVariable tenantSlug: String,
        @RequestParam phone: String,
    ): ResponseEntity<Void> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            campaignService.optOutByPhone(phone)
            ResponseEntity.noContent().build()
        } finally {
            TenantContext.clear()
        }
    }

    /**
     * Configuração pública do programa de fidelidade (Fase 3.3). Sem autenticação —
     * o cardápio precisa desses dados para mostrar a estimativa de pontos ao cliente
     * antes do pedido. Devolve apenas o que o cliente final precisa ver; nunca dados
     * de gestão (saldos, extrato, métricas).
     *
     * Tenant inexistente -> 404. Tenant sem tenant_config -> enabled=false (sem fidelidade).
     */
    @GetMapping("/{tenantSlug}/loyalty-config")
    fun getLoyaltyConfig(@PathVariable tenantSlug: String): ResponseEntity<PublicLoyaltyConfigResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
            ResponseEntity.ok(
                PublicLoyaltyConfigResponse(
                    enabled = config?.loyaltyEnabled ?: false,
                    pointsPerReal = config?.loyaltyPointsPerReal ?: 0,
                    rewardThreshold = config?.loyaltyRewardThreshold ?: 0,
                    rewardDescription = config?.loyaltyRewardDescription,
                ),
            )
        } finally {
            TenantContext.clear()
        }
    }
}

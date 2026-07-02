package com.menuflow.controller

import com.menuflow.dto.ApplyCouponRequest
import com.menuflow.dto.ApplyCouponResponse
import com.menuflow.dto.CategoryResponse
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.PublicProductResponse
import com.menuflow.dto.TrackingRedirectResponse
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
)

data class PublicOrderItemRequest(
    val productId: UUID,
    @field:Positive @field:Max(99) val quantity: Int = 1,
    val notes: String? = null,
    /** Complementos escolhidos (ids de option groups); validados em OrderService.resolveOptions. */
    val optionIds: List<UUID> = emptyList(),
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
)

data class PublicOrderCreatedResponse(
    val orderId: UUID,
    val orderNumber: String,
    val totalCents: Long,
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
            ResponseEntity.ok(
                PublicMenuResponse(categories, products, pixKey, restaurantInfo, deliveryTimes, bestsellerIds),
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
            val paymentMethod = runCatching { PaymentMethod.valueOf(req.paymentMethod) }.getOrNull()
            val notes = buildString {
                append("Cliente: ${req.customerName}")
                req.tableLabel?.let { append(" | Mesa: $it") }
                req.observations?.takeIf { it.isNotBlank() }?.let { append(" | Obs: $it") }
            }
            val orderReq = OrderCreateRequest(
                orderType = OrderType.DINE_IN,
                tableNumber = req.tableLabel,
                customerPhone = req.customerPhone,
                notes = notes,
                paymentMethod = paymentMethod,
                couponCode = req.couponCode,
                items = req.items.map {
                    OrderItemRequest(
                        productId = it.productId,
                        quantity = it.quantity,
                        notes = it.notes,
                        optionIds = it.optionIds,
                    )
                },
            )
            val created = orderService.create(orderReq, null)
            ResponseEntity.ok(PublicOrderCreatedResponse(created.id, created.orderNumber, created.totalCents))
        } finally {
            TenantContext.clear()
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
}

package com.menuflow.controller

import com.menuflow.dto.ApplyCouponRequest
import com.menuflow.dto.ApplyCouponResponse
import com.menuflow.dto.CategoryResponse
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.PublicProductResponse
import com.menuflow.model.OrderType
import com.menuflow.model.PaymentMethod
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.OrderItemRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.CategoryService
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Dados de marca/vitrine do restaurante para o cabecalho do cardapio publico. */
data class RestaurantInfo(
    val name: String?,
    val logoUrl: String?,
    val coverUrl: String?,
    val address: String?,
    val openingHours: String?,
)

data class PublicMenuResponse(
    val categories: List<CategoryResponse>,
    val products: List<PublicProductResponse>,
    /** Chave PIX estatica do restaurante; null quando nao configurada. */
    val pixKey: String?,
    /** Marca/vitrine do restaurante (nome, logo, capa, endereco, horario). */
    val restaurantInfo: RestaurantInfo,
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
            val restaurantInfo = RestaurantInfo(
                name = config?.restaurantName,
                logoUrl = config?.logoUrl,
                coverUrl = config?.coverUrl,
                address = config?.address,
                openingHours = config?.openingHours,
            )
            // So os 5 mais vendidos, apenas ids (nunca contagem/receita no publico).
            val bestsellerIds = orderItemRepository.findTopProductIds(Pageable.ofSize(5))
            ResponseEntity.ok(
                PublicMenuResponse(categories, products, pixKey, restaurantInfo, bestsellerIds),
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

package com.menuflow.controller

import com.menuflow.dto.CategoryResponse
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.PublicProductResponse
import com.menuflow.model.OrderType
import com.menuflow.model.PaymentMethod
import com.menuflow.repository.control.TenantRepository
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

data class PublicMenuResponse(
    val categories: List<CategoryResponse>,
    val products: List<PublicProductResponse>,
    /** Chave PIX estatica do restaurante; null quando nao configurada. */
    val pixKey: String?,
)

data class PublicOrderItemRequest(
    val productId: UUID,
    @field:Positive @field:Max(99) val quantity: Int = 1,
    val notes: String? = null,
)

data class PublicOrderRequest(
    @field:NotBlank val customerName: String,
    @field:NotBlank val paymentMethod: String,
    val tableLabel: String? = null,
    @field:NotEmpty @field:Size(max = 20) val items: List<PublicOrderItemRequest>,
    val observations: String? = null,
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
    private val tenantConfigRepository: TenantConfigRepository,
) {
    @GetMapping("/{tenantSlug}/menu")
    fun getMenu(@PathVariable tenantSlug: String): ResponseEntity<PublicMenuResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            val categories = categoryService.list(Pageable.ofSize(100)).content
            val products = productService.listPublic(Pageable.ofSize(500)).content
            // Dentro do TenantContext: a query roteia para o banco do tenant.
            val pixKey = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()?.pixKey
            ResponseEntity.ok(PublicMenuResponse(categories, products, pixKey))
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
                notes = notes,
                paymentMethod = paymentMethod,
                items = req.items.map { OrderItemRequest(productId = it.productId, quantity = it.quantity, notes = it.notes) },
            )
            val created = orderService.create(orderReq, null)
            ResponseEntity.ok(PublicOrderCreatedResponse(created.id, created.orderNumber, created.totalCents))
        } finally {
            TenantContext.clear()
        }
    }
}

package com.menuflow

import com.menuflow.dto.CouponCreateRequest
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.model.DiscountType
import com.menuflow.model.PaymentMethod
import com.menuflow.repository.tenant.CouponRedemptionRepository
import com.menuflow.service.CouponService
import com.menuflow.service.OrderService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Cupons & Descontos (Fase 3.2) contra um Postgres real (Testcontainers). Prova o
 * cálculo (FIXED/PERCENT, teto no subtotal), as regras de validação (mínimo, janela,
 * maxUses, maxUsesPerCustomer) e que aplicar o cupom num pedido registra a redenção.
 *
 * Cada caso usa seu PRÓPRIO tenant (db isolado). Não é @Transactional: cada save
 * commita, então as contagens enxergam o que foi semeado antes. Os testes que
 * precisam de uma redenção real passam por orderService.create (a tabela
 * coupon_redemptions tem FK para orders, então não dá para inserir redenção avulsa).
 */
class CouponServiceTest @Autowired constructor(
    private val couponService: CouponService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val redemptionRepository: CouponRedemptionRepository,
) : IntegrationTestBase() {

    private lateinit var tenant: String

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun bind(): String {
        tenant = "coupon_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        return tenant
    }

    private fun newCoupon(
        code: String = "PROMO${UUID.randomUUID().toString().take(4)}",
        type: DiscountType = DiscountType.FIXED,
        value: Long = 500,
        minOrder: Long = 0,
        maxUses: Int? = null,
        maxPerCustomer: Int = 1,
        validFrom: Instant = Instant.now().minus(1, ChronoUnit.DAYS),
        validUntil: Instant = Instant.now().plus(1, ChronoUnit.DAYS),
    ): String {
        TenantContext.set(tenant)
        couponService.create(
            CouponCreateRequest(
                code = code,
                discountType = type,
                discountValue = value,
                minOrderCents = minOrder,
                maxUses = maxUses,
                maxUsesPerCustomer = maxPerCustomer,
                validFrom = validFrom,
                validUntil = validUntil,
            ),
            actorId = null,
        )
        return code
    }

    /** Produto simples (sem ficha técnica -> sem baixa de estoque) para gerar pedidos. */
    private fun newProduct(price: Long = 2_000): UUID {
        TenantContext.set(tenant)
        return productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "CUP-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = price,
            ),
        ).id
    }

    /** Pedido público (userId=null -> sem caixa) usando o cupom. */
    private fun placeOrder(productId: UUID, couponCode: String?, phone: String? = null, qty: Int = 1): OrderResponseLike {
        TenantContext.set(tenant)
        val r = orderService.create(
            OrderCreateRequest(
                items = listOf(OrderItemRequest(productId = productId, quantity = qty)),
                couponCode = couponCode,
                customerPhone = phone,
                paymentMethod = PaymentMethod.PIX,
            ),
            userId = null,
        )
        return OrderResponseLike(r.totalCents, r.discountCents)
    }

    data class OrderResponseLike(val totalCents: Long, val discountCents: Long)

    @Test
    fun `cupom FIXED aplica desconto correto`() {
        bind()
        val code = newCoupon(type = DiscountType.FIXED, value = 500)
        val app = couponService.preview(code, subtotalCents = 5_000, customerPhone = null)
        assertEquals(500, app.discountCents)
    }

    @Test
    fun `cupom PERCENT aplica desconto correto`() {
        bind()
        val code = newCoupon(type = DiscountType.PERCENT, value = 1_500) // 15%
        val app = couponService.preview(code, subtotalCents = 5_000, customerPhone = null)
        assertEquals(750, app.discountCents, "15% de 5000")
    }

    @Test
    fun `desconto FIXED nunca ultrapassa o subtotal`() {
        bind()
        val code = newCoupon(type = DiscountType.FIXED, value = 9_999)
        val app = couponService.preview(code, subtotalCents = 3_000, customerPhone = null)
        assertEquals(3_000, app.discountCents, "limitado ao subtotal")
    }

    @Test
    fun `pedido abaixo do minimo lança exceção`() {
        bind()
        val code = newCoupon(minOrder = 5_000)
        assertThrows(BusinessException::class.java) {
            couponService.preview(code, subtotalCents = 3_000, customerPhone = null)
        }
    }

    @Test
    fun `cupom esgotado (maxUses) lança exceção`() {
        bind()
        val product = newProduct()
        val code = newCoupon(maxUses = 1)
        // 1o uso consome o único disponível.
        placeOrder(product, code)
        // 2o uso: esgotado.
        assertThrows(BusinessException::class.java) {
            couponService.preview(code, subtotalCents = 2_000, customerPhone = null)
        }
    }

    @Test
    fun `cupom já utilizado pelo cliente (maxUsesPerCustomer) lança exceção`() {
        bind()
        val product = newProduct()
        val code = newCoupon(maxUses = 99, maxPerCustomer = 1)
        placeOrder(product, code, phone = "91999990000")
        // Mesmo telefone: bloqueado.
        TenantContext.set(tenant)
        assertThrows(BusinessException::class.java) {
            couponService.preview(code, subtotalCents = 2_000, customerPhone = "91999990000")
        }
        // Outro telefone: ainda pode.
        TenantContext.set(tenant)
        val ok = couponService.preview(code, subtotalCents = 2_000, customerPhone = "91988887777")
        assertEquals(500, ok.discountCents)
    }

    @Test
    fun `cupom expirado lança exceção`() {
        bind()
        val code = newCoupon(
            validFrom = Instant.now().minus(10, ChronoUnit.DAYS),
            validUntil = Instant.now().minus(1, ChronoUnit.DAYS),
        )
        assertThrows(BusinessException::class.java) {
            couponService.preview(code, subtotalCents = 5_000, customerPhone = null)
        }
    }

    @Test
    fun `aplicar cupom no pedido registra redenção e abate o total`() {
        bind()
        val product = newProduct(price = 2_000)
        val code = newCoupon(type = DiscountType.FIXED, value = 500)

        val order = placeOrder(product, code, phone = "91999991111")

        assertEquals(500, order.discountCents, "desconto do cupom entra em discountCents")
        assertEquals(1_500, order.totalCents, "2000 - 500")

        // Uma redenção registrada para o cupom.
        TenantContext.set(tenant)
        val coupon = couponService.list(active = true, pageable = org.springframework.data.domain.PageRequest.of(0, 10))
            .content.first { it.code.equals(code, ignoreCase = true) }
        assertEquals(1, redemptionRepository.countByCouponId(coupon.id))
        assertEquals(1, redemptionRepository.countByCouponIdAndCustomerPhone(coupon.id, "91999991111"))
    }
}

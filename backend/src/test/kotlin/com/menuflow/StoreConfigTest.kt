package com.menuflow

import com.menuflow.dto.CancellationReasonRequest
import com.menuflow.dto.MenuLinkRequest
import com.menuflow.dto.OrderCreateRequest
import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.OrderStatusUpdateRequest
import com.menuflow.dto.PaymentMethodConfigUpsertRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.TenantConfigUpdateRequest
import com.menuflow.model.MenuLinkVariant
import com.menuflow.model.OrderStatus
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.service.CancellationReasonService
import com.menuflow.service.MenuLinkService
import com.menuflow.service.OrderService
import com.menuflow.service.PaymentMethodConfigService
import com.menuflow.service.ProductService
import com.menuflow.service.TenantConfigService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID

/**
 * Fase CONFIG-A (issues #6-#11) contra um Postgres real (Testcontainers). Prova os
 * campos/endpoints novos de config de loja: endereco estruturado + pin (#7), tempos
 * por modalidade (#9), formas de pagamento (#8), motivos de cancelamento e o
 * carimbo no pedido (#10) e variantes de link/QR (#11). Cada caso usa seu PROPRIO
 * tenant (db isolado, provisionado no 1o acesso pelo routing datasource).
 */
class StoreConfigTest @Autowired constructor(
    private val tenantConfigService: TenantConfigService,
    private val paymentMethodConfigService: PaymentMethodConfigService,
    private val cancellationReasonService: CancellationReasonService,
    private val menuLinkService: MenuLinkService,
    private val orderService: OrderService,
    private val productService: ProductService,
    private val orderRepository: OrderRepository,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun bind(): String {
        val tenant = "cfg_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        return tenant
    }

    private fun newProduct(price: Long = 2_000): UUID {
        return productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "CFG-${UUID.randomUUID().toString().take(6)}",
                name = "Burger",
                priceCents = price,
            ),
        ).id
    }

    // --- #7 endereco estruturado + pin, #9 tempos por modalidade ---
    @Test
    fun `config persists structured address, map pin and modality times`() {
        bind()
        val resp = tenantConfigService.update(
            TenantConfigUpdateRequest(
                autoAcceptOrders = false,
                postalCode = "68900-000",
                street = "Avenida Central",
                streetNumber = "123A",
                addressComplement = "Loja 2",
                neighborhood = "Centro",
                merchantCity = "Macapa",
                stateUf = "ap",
                restaurantLat = 0.0349,
                restaurantLng = -51.0694,
                deliveryTimeMinMinutes = 40,
                deliveryTimeMaxMinutes = 70,
            ),
        )
        assertEquals("68900-000", resp.postalCode)
        assertEquals("Avenida Central", resp.street)
        assertEquals("123A", resp.streetNumber)
        assertEquals("Loja 2", resp.addressComplement)
        assertEquals("Centro", resp.neighborhood)
        assertEquals("AP", resp.stateUf, "UF normalizada para maiuscula")
        assertEquals(0.0349, resp.restaurantLat)
        assertEquals(-51.0694, resp.restaurantLng)
        assertEquals(40, resp.deliveryTimeMinMinutes)
        assertEquals(70, resp.deliveryTimeMaxMinutes)
        // Defaults das outras modalidades preservados.
        assertEquals(15, resp.pickupTimeMinMinutes)
        assertEquals(20, resp.dineinTimeMaxMinutes)

        // Persistiu de fato (releitura).
        assertEquals("Avenida Central", tenantConfigService.get().street)
    }

    @Test
    fun `modality time min greater than max is rejected`() {
        bind()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            tenantConfigService.update(
                TenantConfigUpdateRequest(
                    autoAcceptOrders = false,
                    deliveryTimeMinMinutes = 90,
                    deliveryTimeMaxMinutes = 30,
                ),
            )
        }
        assertTrue(ex.message!!.contains("delivery"))
    }

    @Test
    fun `latitude out of range is rejected`() {
        bind()
        assertThrows(IllegalArgumentException::class.java) {
            tenantConfigService.update(
                TenantConfigUpdateRequest(autoAcceptOrders = false, restaurantLat = 120.0),
            )
        }
    }

    // --- #8 formas de pagamento ---
    @Test
    fun `payment methods are seeded and upsert updates fee and passthrough without duplicating`() {
        bind()
        val seeded = paymentMethodConfigService.list()
        val methods = seeded.map { it.method }.toSet()
        assertTrue(methods.containsAll(setOf("PIX", "CASH", "CREDIT_CARD", "DEBIT_CARD", "MEAL_VOUCHER")))
        val seededCount = seeded.size

        val updated = paymentMethodConfigService.upsert(
            PaymentMethodConfigUpsertRequest(
                method = "CREDIT_CARD",
                label = "Cartao de credito",
                enabled = true,
                feePct = BigDecimal("3.50"),
                passFeeToCustomer = true,
                sortOrder = 30,
            ),
        )
        assertEquals(0, BigDecimal("3.50").compareTo(updated.feePct))
        assertTrue(updated.passFeeToCustomer)

        // Upsert pela chave natural NAO cria linha nova.
        assertEquals(seededCount, paymentMethodConfigService.list().size)
        val card = paymentMethodConfigService.list().first { it.method == "CREDIT_CARD" }
        assertTrue(card.passFeeToCustomer)
    }

    // --- #10 motivos de cancelamento + carimbo no pedido ---
    @Test
    fun `cancellation reasons are seeded, editable and stamp the cancelled order`() {
        bind()
        // V47 semeia motivos padrao.
        assertTrue(cancellationReasonService.list(activeOnly = true).isNotEmpty())

        val reason = cancellationReasonService.create(
            CancellationReasonRequest(description = "Cozinha sobrecarregada", sortOrder = 5),
        )
        assertNotNull(reason.id)

        // Cria um pedido e cancela citando o motivo do catalogo.
        val productId = newProduct()
        val order = orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 1))),
            null,
        )
        orderService.updateStatus(
            order.id,
            OrderStatusUpdateRequest(status = OrderStatus.CANCELLED, cancelledReasonId = reason.id),
        )
        val persisted = orderRepository.findById(order.id).get()
        assertEquals(reason.id, persisted.cancelledReasonId)
        assertEquals("Cozinha sobrecarregada", persisted.cancelledReason, "texto do motivo denormalizado no pedido")
    }

    @Test
    fun `cancelling with an unknown reason id is rejected`() {
        bind()
        val productId = newProduct()
        val order = orderService.create(
            OrderCreateRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 1))),
            null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            orderService.updateStatus(
                order.id,
                OrderStatusUpdateRequest(status = OrderStatus.CANCELLED, cancelledReasonId = UUID.randomUUID()),
            )
        }
    }

    @Test
    fun `deactivated cancellation reason drops from the active selector`() {
        bind()
        val reason = cancellationReasonService.create(CancellationReasonRequest(description = "Teste inativar"))
        cancellationReasonService.deactivate(reason.id!!)
        val active = cancellationReasonService.list(activeOnly = true).map { it.description }
        assertFalse(active.contains("Teste inativar"))
        // Continua listado quando activeOnly=false (preserva historico/relatorio).
        assertTrue(cancellationReasonService.list(activeOnly = false).any { it.description == "Teste inativar" })
    }

    // --- #11 variantes de link/QR ---
    @Test
    fun `menu link crud and public resolve honour the variant`() {
        bind()
        val viewOnly = menuLinkService.create(
            MenuLinkRequest(slug = "vitrine", variant = MenuLinkVariant.VIEW_ONLY, label = "Vitrine"),
        )
        assertEquals(MenuLinkVariant.VIEW_ONLY, viewOnly.variant)

        val resolved = menuLinkService.resolvePublic("vitrine")
        assertEquals(MenuLinkVariant.VIEW_ONLY, resolved.variant)
        assertFalse(resolved.orderingEnabled, "VIEW_ONLY nao habilita pedido")

        val full = menuLinkService.create(
            MenuLinkRequest(slug = "delivery", variant = MenuLinkVariant.FULL, label = "Delivery"),
        )
        assertTrue(menuLinkService.resolvePublic("delivery").orderingEnabled)
        assertEquals(2, menuLinkService.list().size)
        assertNotNull(full.id)
    }

    @Test
    fun `counter link requires a table and duplicate active slug is rejected`() {
        bind()
        // COUNTER sem mesa -> 400.
        assertThrows(IllegalArgumentException::class.java) {
            menuLinkService.create(
                MenuLinkRequest(slug = "balcao", variant = MenuLinkVariant.COUNTER, label = "Balcao"),
            )
        }
        // Slug duplicado entre ativos -> 400.
        menuLinkService.create(MenuLinkRequest(slug = "menu", variant = MenuLinkVariant.FULL, label = "A"))
        assertThrows(IllegalArgumentException::class.java) {
            menuLinkService.create(MenuLinkRequest(slug = "menu", variant = MenuLinkVariant.FULL, label = "B"))
        }
    }
}

package com.menuflow

import com.menuflow.dto.EntryPopupUpdateRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.TenantConfigUpdateRequest
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.service.EntryPopupService
import com.menuflow.service.ProductService
import com.menuflow.service.TenantConfigService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Fase CONFIG-B (issues #12 tema, #13 pop-up de entrada) contra um Postgres real
 * (Testcontainers). Prova: cor de marca + toggles + contraste WCAG calculado no
 * servidor (#12); pop-up com guard-rail de ate 3 produtos, validacao de produto
 * ativo, substituicao atomica e filtro de inativos no cardapio publico (#13).
 * Cada caso usa seu PROPRIO tenant (db isolado, provisionado no 1o acesso).
 */
class ThemeAndEntryPopupTest @Autowired constructor(
    private val tenantConfigService: TenantConfigService,
    private val entryPopupService: EntryPopupService,
    private val productService: ProductService,
    private val tenantConfigRepository: TenantConfigRepository,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun bind(): String {
        val tenant = "cfgb_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)
        return tenant
    }

    private fun newProduct(name: String = "Burger"): UUID =
        productService.create(
            ProductCreateRequest(
                categoryId = UUID.randomUUID(),
                sku = "CFGB-${UUID.randomUUID().toString().take(6)}",
                name = name,
                priceCents = 2_000,
            ),
        ).id

    // --- #12 tema: cor + toggles + contraste ---
    @Test
    fun `theme persists color and toggles and computes WCAG contrast`() {
        bind()
        val resp = tenantConfigService.update(
            TenantConfigUpdateRequest(
                autoAcceptOrders = false,
                themePrimaryColor = "#ff0000",
                themeShowPrices = true,
                themeShowDescriptions = false,
                themeShowPhotos = false,
            ),
        )
        assertEquals("#FF0000", resp.themePrimaryColor, "hex normalizado para maiusculo")
        assertTrue(resp.themeShowPrices)
        assertFalse(resp.themeShowDescriptions)
        assertFalse(resp.themeShowPhotos)

        val c = resp.themeContrast!!
        assertEquals("#FF0000", c.primaryColor)
        // Vermelho puro: contraste ~4.0 vs branco, ~5.25 vs preto -> texto preto.
        assertTrue(c.ratioOnWhite in 3.9..4.1, "ratioOnWhite=${c.ratioOnWhite}")
        assertTrue(c.ratioOnBlack in 5.1..5.4, "ratioOnBlack=${c.ratioOnBlack}")
        assertEquals("#000000", c.recommendedTextColor)
        assertTrue(c.meetsAA)

        // Persistiu (releitura).
        assertEquals("#FF0000", tenantConfigService.get().themePrimaryColor)
    }

    @Test
    fun `dark brand color recommends white text`() {
        bind()
        val c = tenantConfigService.update(
            TenantConfigUpdateRequest(autoAcceptOrders = false, themePrimaryColor = "#0000FF"),
        ).themeContrast!!
        assertEquals("#FFFFFF", c.recommendedTextColor)
        assertTrue(c.meetsAA)
    }

    @Test
    fun `invalid hex color is rejected`() {
        bind()
        assertThrows(IllegalArgumentException::class.java) {
            tenantConfigService.update(
                TenantConfigUpdateRequest(autoAcceptOrders = false, themePrimaryColor = "vermelho"),
            )
        }
    }

    @Test
    fun `empty color clears the theme color`() {
        bind()
        tenantConfigService.update(TenantConfigUpdateRequest(autoAcceptOrders = false, themePrimaryColor = "#123456"))
        val resp = tenantConfigService.update(TenantConfigUpdateRequest(autoAcceptOrders = false, themePrimaryColor = ""))
        assertNull(resp.themePrimaryColor)
        assertNull(resp.themeContrast)
        // Toggles default ligados quando nunca alterados.
        assertTrue(resp.themeShowPrices)
    }

    // --- #13 pop-up de entrada ---
    @Test
    fun `entry popup starts disabled and empty`() {
        bind()
        val popup = entryPopupService.get()
        assertFalse(popup.enabled)
        assertTrue(popup.products.isEmpty())
    }

    @Test
    fun `entry popup upsert stores ordered products and toggle`() {
        bind()
        val p1 = newProduct("Combo A")
        val p2 = newProduct("Combo B")
        val resp = entryPopupService.update(
            EntryPopupUpdateRequest(enabled = true, title = "Sugestoes da casa", productIds = listOf(p2, p1)),
        )
        assertTrue(resp.enabled)
        assertEquals("Sugestoes da casa", resp.title)
        assertEquals(listOf(p2, p1), resp.products.map { it.productId }, "ordem preservada e sortOrder 0..n")
        assertEquals(0, resp.products.first().sortOrder)
    }

    @Test
    fun `entry popup rejects more than three products (guard-rail)`() {
        bind()
        val ids = (1..4).map { newProduct("P$it") }
        assertThrows(IllegalArgumentException::class.java) {
            entryPopupService.update(EntryPopupUpdateRequest(enabled = true, productIds = ids))
        }
    }

    @Test
    fun `entry popup accepts exactly three products`() {
        bind()
        val ids = (1..3).map { newProduct("P$it") }
        val resp = entryPopupService.update(EntryPopupUpdateRequest(enabled = true, productIds = ids))
        assertEquals(3, resp.products.size)
    }

    @Test
    fun `entry popup dedupes repeated product ids`() {
        bind()
        val p1 = newProduct("Unico")
        val resp = entryPopupService.update(
            EntryPopupUpdateRequest(enabled = true, productIds = listOf(p1, p1, p1)),
        )
        assertEquals(1, resp.products.size)
    }

    @Test
    fun `entry popup rejects unknown or inactive product`() {
        bind()
        // Id inexistente.
        assertThrows(IllegalArgumentException::class.java) {
            entryPopupService.update(EntryPopupUpdateRequest(enabled = true, productIds = listOf(UUID.randomUUID())))
        }
        // Produto desativado (soft-delete).
        val dead = newProduct("Sai de cartaz")
        productService.delete(dead)
        assertThrows(IllegalArgumentException::class.java) {
            entryPopupService.update(EntryPopupUpdateRequest(enabled = true, productIds = listOf(dead)))
        }
    }

    @Test
    fun `entry popup replace semantics overwrite previous list`() {
        bind()
        val p1 = newProduct("A")
        val p2 = newProduct("B")
        entryPopupService.update(EntryPopupUpdateRequest(enabled = true, productIds = listOf(p1, p2)))
        // Substitui por um so produto.
        val resp = entryPopupService.update(EntryPopupUpdateRequest(enabled = true, productIds = listOf(p2)))
        assertEquals(listOf(p2), resp.products.map { it.productId })
    }

    @Test
    fun `public menu popup hides inactive products and respects disabled toggle`() {
        bind()
        val p1 = newProduct("Vivo")
        val p2 = newProduct("Vai morrer")
        entryPopupService.update(EntryPopupUpdateRequest(enabled = true, title = "Top", productIds = listOf(p1, p2)))

        // Desativa p2: some do publico (mas continua na visao de gestao).
        productService.delete(p2)
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
        val publicPopup = entryPopupService.getForPublicMenu(config)
        assertTrue(publicPopup.enabled)
        assertEquals(listOf(p1), publicPopup.products.map { it.id })
        // Gestao ainda ve os 2 (com active=false no obsoleto).
        assertEquals(2, entryPopupService.get().products.size)
        assertNotNull(entryPopupService.get().products.first { it.productId == p2 })
        assertFalse(entryPopupService.get().products.first { it.productId == p2 }.active)

        // Desliga o pop-up: publico vem vazio.
        entryPopupService.update(EntryPopupUpdateRequest(enabled = false, productIds = listOf(p1)))
        val cfg2 = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
        val off = entryPopupService.getForPublicMenu(cfg2)
        assertFalse(off.enabled)
        assertTrue(off.products.isEmpty())
    }
}

package com.menuflow

import com.menuflow.dto.ProductCreateRequest
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import java.util.UUID

/**
 * Proves database-per-tenant isolation: a product written while bound to tenant
 * "alpha" must be invisible while bound to tenant "beta", because each tenant
 * routes to a physically different database.
 */
class TenantIsolationTest @Autowired constructor(
    private val productService: ProductService,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    @Test
    fun `product created in tenant alpha is not visible in tenant beta`() {
        val req = ProductCreateRequest(
            categoryId = UUID.randomUUID(),
            sku = "X-BURGER",
            name = "X-Burger",
            priceCents = 2500,
        )

        // Write into tenant "alpha"
        TenantContext.set("alpha")
        val created = productService.create(req)
        assertEquals(2500, created.priceCents)

        // Read back in "alpha": present
        TenantContext.set("alpha")
        val alphaList = productService.list(PageRequest.of(0, 50))
        assertTrue(alphaList.content.any { it.id == created.id }, "alpha must see its own product")

        // Read in "beta": absent (different physical database)
        TenantContext.set("beta")
        val betaList = productService.list(PageRequest.of(0, 50))
        assertTrue(betaList.content.none { it.id == created.id }, "beta must NOT see alpha's product")
        assertEquals(0, betaList.totalElements, "beta database should be empty")
    }

    @Test
    fun `same SKU can exist independently in two tenants`() {
        val mk = { ProductCreateRequest(UUID.randomUUID(), "COMBO-1", "Combo", priceCents = 1000) }

        TenantContext.set("alpha")
        productService.create(mk())

        // Same SKU in a different tenant must NOT conflict.
        TenantContext.set("gamma")
        val gamma = productService.create(mk())
        assertEquals("COMBO-1", gamma.sku)
    }
}

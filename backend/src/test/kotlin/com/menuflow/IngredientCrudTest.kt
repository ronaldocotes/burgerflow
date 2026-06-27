package com.menuflow

import com.menuflow.dto.IngredientRequest
import com.menuflow.exception.BusinessException
import com.menuflow.service.IngredientService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired

/** CRUD de insumos (Ingredient) — base da ficha técnica/CMV. */
class IngredientCrudTest @Autowired constructor(
    private val service: IngredientService,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    @Test
    fun `create and list ingredient`() {
        TenantContext.set("ingt1")
        val created = service.create(
            IngredientRequest(name = "Queijo", unit = "GRAM", unitCostCents = 50, stockQuantity = 1000.0, minStock = 100.0),
        )
        assertEquals("Queijo", created.name)
        assertEquals("GRAM", created.unit)
        assertEquals(50, created.unitCostCents)
        assertTrue(service.list().any { it.id == created.id })
    }

    @Test
    fun `duplicate name is rejected`() {
        TenantContext.set("ingt2")
        service.create(IngredientRequest(name = "Bacon"))
        assertThrows<BusinessException> { service.create(IngredientRequest(name = "Bacon")) }
    }

    @Test
    fun `invalid unit is rejected`() {
        TenantContext.set("ingt3")
        assertThrows<BusinessException> { service.create(IngredientRequest(name = "Farinha", unit = "TONELADA")) }
    }

    @Test
    fun `soft-deleted ingredient drops out of the list`() {
        TenantContext.set("ingt4")
        val c = service.create(IngredientRequest(name = "Tomate"))
        service.delete(c.id)
        assertTrue(service.list().none { it.id == c.id })
    }
}

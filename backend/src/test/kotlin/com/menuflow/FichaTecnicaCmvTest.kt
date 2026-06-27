package com.menuflow

import com.menuflow.dto.IngredientRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.RecipeItemRequest
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.service.IngredientService
import com.menuflow.service.ProductRecipeService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** Ficha técnica (Product↔Ingredient) + CMV = soma(quantity * unitCostCents). */
class FichaTecnicaCmvTest @Autowired constructor(
    private val recipeService: ProductRecipeService,
    private val ingredientService: IngredientService,
    private val productService: ProductService,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun newProduct(sku: String) = productService.create(
        ProductCreateRequest(categoryId = UUID.randomUUID(), sku = sku, name = "Burger", priceCents = 3000),
    ).id

    @Test
    fun `recipe lines build the CMV sum`() {
        TenantContext.set("cmv1")
        val product = newProduct("BURG-CMV-${UUID.randomUUID().toString().take(5)}")
        val pao = ingredientService.create(IngredientRequest(name = "Pao", unitCostCents = 100))
        val carne = ingredientService.create(IngredientRequest(name = "Carne", unitCostCents = 500))

        recipeService.upsert(product, pao.id, RecipeItemRequest(quantity = 2.0)) // 2 * 100 = 200
        recipeService.upsert(product, carne.id, RecipeItemRequest(quantity = 1.0)) // 1 * 500 = 500

        val cmv = recipeService.cmv(product)
        assertEquals(700, cmv.cmvCents, "CMV = 200 + 500")
        assertEquals(2, cmv.items.size)
    }

    @Test
    fun `upsert updates the existing recipe line`() {
        TenantContext.set("cmv2")
        val product = newProduct("BURG-CMV2-${UUID.randomUUID().toString().take(5)}")
        val queijo = ingredientService.create(IngredientRequest(name = "Queijo", unitCostCents = 200))

        recipeService.upsert(product, queijo.id, RecipeItemRequest(quantity = 1.0))
        recipeService.upsert(product, queijo.id, RecipeItemRequest(quantity = 3.0))

        val recipe = recipeService.listRecipe(product)
        assertEquals(1, recipe.size, "upsert não duplica a linha")
        assertEquals(3.0, recipe[0].quantity)
        assertEquals(600, recipe[0].lineCostCents) // 3 * 200
    }

    @Test
    fun `recipe of unknown product is rejected`() {
        TenantContext.set("cmv3")
        assertThrows<ResourceNotFoundException> { recipeService.listRecipe(UUID.randomUUID()) }
    }
}

package com.menuflow.service

import com.menuflow.dto.ProductCmvResponse
import com.menuflow.dto.RecipeItemRequest
import com.menuflow.dto.RecipeItemResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.Ingredient
import com.menuflow.model.IngredientUnit
import com.menuflow.model.ProductIngredient
import com.menuflow.model.ProductIngredientId
import com.menuflow.repository.tenant.IngredientRepository
import com.menuflow.repository.tenant.ProductIngredientRepository
import com.menuflow.repository.tenant.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Ficha técnica (Product ↔ Ingredient) e CMV derivado. Dinheiro em centavos:
 * lineCost = round(quantity * unitCostCents); CMV = soma das linhas.
 */
@Service
class ProductRecipeService(
    private val productRepository: ProductRepository,
    private val ingredientRepository: IngredientRepository,
    private val productIngredientRepository: ProductIngredientRepository,
) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun listRecipe(productId: UUID): List<RecipeItemResponse> {
        ensureProduct(productId)
        return build(productId)
    }

    @Transactional("tenantTransactionManager")
    fun upsert(productId: UUID, ingredientId: UUID, req: RecipeItemRequest): RecipeItemResponse {
        ensureProduct(productId)
        val ing = ingredientRepository.findById(ingredientId)
            .orElseThrow { ResourceNotFoundException("Insumo não encontrado: $ingredientId") }
        val pi = productIngredientRepository.findById(ProductIngredientId(productId, ingredientId))
            .orElse(ProductIngredient(productId = productId, ingredientId = ingredientId, quantity = req.quantity))
        pi.quantity = req.quantity
        pi.unit = parseUnit(req.unit)
        pi.isOptional = req.isOptional
        return toResponse(productIngredientRepository.save(pi), ing)
    }

    @Transactional("tenantTransactionManager")
    fun remove(productId: UUID, ingredientId: UUID) {
        ensureProduct(productId)
        val id = ProductIngredientId(productId, ingredientId)
        if (!productIngredientRepository.existsById(id)) {
            throw ResourceNotFoundException("Item de ficha técnica não encontrado")
        }
        productIngredientRepository.deleteById(id)
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun cmv(productId: UUID): ProductCmvResponse {
        ensureProduct(productId)
        val items = build(productId)
        return ProductCmvResponse(productId, items.sumOf { it.lineCostCents }, items)
    }

    private fun build(productId: UUID): List<RecipeItemResponse> {
        val lines = productIngredientRepository.findByProductId(productId)
        if (lines.isEmpty()) return emptyList()
        val ingById = ingredientRepository.findAllById(lines.map { it.ingredientId }).associateBy { it.id }
        return lines.sortedBy { it.displayOrder }.mapNotNull { pi ->
            ingById[pi.ingredientId]?.let { toResponse(pi, it) }
        }
    }

    private fun toResponse(pi: ProductIngredient, ing: Ingredient) = RecipeItemResponse(
        ingredientId = ing.id!!,
        ingredientName = ing.name,
        quantity = pi.quantity,
        unit = pi.unit.name,
        isOptional = pi.isOptional,
        unitCostCents = ing.unitCostCents,
        lineCostCents = Math.round(pi.quantity * ing.unitCostCents),
    )

    private fun ensureProduct(productId: UUID) {
        productRepository.findById(productId)
            .orElseThrow { ResourceNotFoundException("Produto não encontrado: $productId") }
    }

    private fun parseUnit(raw: String): IngredientUnit =
        runCatching { IngredientUnit.valueOf(raw.uppercase()) }
            .getOrElse { throw BusinessException("Unidade inválida: $raw") }
}

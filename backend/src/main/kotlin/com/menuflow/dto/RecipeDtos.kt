package com.menuflow.dto

import jakarta.validation.constraints.Positive
import java.util.UUID

/** Linha da ficha técnica: quanto de um insumo uma unidade do produto consome. */
data class RecipeItemRequest(
    @field:Positive val quantity: Double,
    /** Unidade do enum IngredientUnit. */
    val unit: String = "UNIT",
    val isOptional: Boolean = false,
)

data class RecipeItemResponse(
    val ingredientId: UUID,
    val ingredientName: String,
    val quantity: Double,
    val unit: String,
    val isOptional: Boolean,
    val unitCostCents: Long,
    /** Custo desta linha (centavos) = round(quantity * unitCostCents). */
    val lineCostCents: Long,
)

/** CMV (custo da mercadoria vendida) de um produto = soma das linhas da ficha técnica. */
data class ProductCmvResponse(
    val productId: UUID,
    val cmvCents: Long,
    val items: List<RecipeItemResponse>,
)

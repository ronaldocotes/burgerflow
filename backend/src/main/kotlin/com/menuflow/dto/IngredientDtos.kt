package com.menuflow.dto

import com.menuflow.model.Ingredient
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.util.UUID

data class IngredientRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Size(max = 255) val description: String = "",
    /** Unidade do enum IngredientUnit (UNIT, GRAM, KILOGRAM, MILLILITER, LITER, PIECE, BOX). */
    val unit: String = "UNIT",
    @field:PositiveOrZero val unitCostCents: Long = 0,
    @field:PositiveOrZero val stockQuantity: Double = 0.0,
    @field:PositiveOrZero val minStock: Double = 0.0,
    val isAllergen: Boolean = false,
)

data class IngredientResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val unit: String,
    val unitCostCents: Long,
    val stockQuantity: Double,
    val minStock: Double,
    val isAllergen: Boolean,
    val active: Boolean,
    val lowStock: Boolean,
) {
    companion object {
        fun from(i: Ingredient) = IngredientResponse(
            id = i.id!!,
            name = i.name,
            description = i.description,
            unit = i.unit.name,
            unitCostCents = i.unitCostCents,
            stockQuantity = i.stockQuantity,
            minStock = i.minStock,
            isAllergen = i.isAllergen,
            active = i.active,
            lowStock = i.isLowStock(),
        )
    }
}

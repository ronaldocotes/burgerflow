package com.burgerflow.dto

import com.burgerflow.model.Category
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class CategoryRequest(
    @field:NotBlank val name: String,
    val description: String = "",
    val displayOrder: Int = 0,
    val colorCode: String? = null,
    val iconUrl: String? = null,
)

data class CategoryResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val displayOrder: Int,
    val active: Boolean,
    val colorCode: String?,
    val iconUrl: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(c: Category) = CategoryResponse(
            id = c.id!!,
            name = c.name,
            description = c.description,
            displayOrder = c.displayOrder,
            active = c.active,
            colorCode = c.colorCode,
            iconUrl = c.iconUrl,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
        )
    }
}

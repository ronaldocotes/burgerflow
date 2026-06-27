package com.menuflow.dto

import com.menuflow.model.Product
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import java.time.Instant
import java.util.UUID

/** Allowlisted create payload: clients cannot set id, active, version, timestamps. */
data class ProductCreateRequest(
    val categoryId: UUID,
    @field:NotBlank val sku: String,
    @field:NotBlank val name: String,
    val description: String = "",
    @field:Positive val priceCents: Long,
    @field:PositiveOrZero val costPriceCents: Long? = null,
    val imageUrl: String? = null,
    val isAvailable: Boolean = true,
    val displayOrder: Int = 0,
    val preparationTimeMinutes: Int = 10,
    val isFeatured: Boolean = false,
    @field:PositiveOrZero val promoPriceCents: Long? = null,
    val promoStartsAt: Instant? = null,
    val promoEndsAt: Instant? = null,
)

data class ProductUpdateRequest(
    val categoryId: UUID,
    @field:NotBlank val sku: String,
    @field:NotBlank val name: String,
    val description: String = "",
    @field:Positive val priceCents: Long,
    @field:PositiveOrZero val costPriceCents: Long? = null,
    val imageUrl: String? = null,
    val isAvailable: Boolean = true,
    val displayOrder: Int = 0,
    val preparationTimeMinutes: Int = 10,
    val isFeatured: Boolean = false,
    @field:PositiveOrZero val promoPriceCents: Long? = null,
    val promoStartsAt: Instant? = null,
    val promoEndsAt: Instant? = null,
)

data class ProductResponse(
    val id: UUID,
    val categoryId: UUID,
    val sku: String,
    val name: String,
    val description: String,
    val priceCents: Long,
    val costPriceCents: Long?,
    val imageUrl: String?,
    val active: Boolean,
    val isAvailable: Boolean,
    val displayOrder: Int,
    val preparationTimeMinutes: Int,
    val isFeatured: Boolean,
    val promoPriceCents: Long?,
    val promoStartsAt: Instant?,
    val promoEndsAt: Instant?,
    val effectivePriceCents: Long,
    val onPromo: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(p: Product) = ProductResponse(
            id = p.id!!,
            categoryId = p.categoryId,
            sku = p.sku,
            name = p.name,
            description = p.description,
            priceCents = p.priceCents,
            costPriceCents = p.costPriceCents,
            imageUrl = p.imageUrl,
            active = p.active,
            isAvailable = p.isAvailable,
            displayOrder = p.displayOrder,
            preparationTimeMinutes = p.preparationTimeMinutes,
            isFeatured = p.isFeatured,
            promoPriceCents = p.promoPriceCents,
            promoStartsAt = p.promoStartsAt,
            promoEndsAt = p.promoEndsAt,
            effectivePriceCents = p.effectivePriceCents(),
            onPromo = p.isOnPromo(),
            createdAt = p.createdAt,
            updatedAt = p.updatedAt,
        )
    }
}


/** DTO publico do produto (cardapio /public): sem custo, SKU nem timestamps internos. */
data class PublicProductResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val categoryId: UUID,
    val priceCents: Long,
    val effectivePriceCents: Long,
    val imageUrl: String?,
    val isAvailable: Boolean,
    val onPromo: Boolean,
    val isFeatured: Boolean,
    val displayOrder: Int,
) {
    companion object {
        fun from(p: Product) = PublicProductResponse(
            id = p.id!!,
            name = p.name,
            description = p.description,
            categoryId = p.categoryId,
            priceCents = p.priceCents,
            effectivePriceCents = p.effectivePriceCents(),
            imageUrl = p.imageUrl,
            isAvailable = p.isAvailable,
            onPromo = p.isOnPromo(),
            isFeatured = p.isFeatured,
            displayOrder = p.displayOrder,
        )
    }
}

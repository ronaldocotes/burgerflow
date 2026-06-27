package com.menuflow.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import java.util.UUID

data class ProductSizeRequest(val name: String, val code: String, val priceCents: Long)
data class ProductSizeResponse(val id: UUID, val name: String, val code: String, val priceCents: Long, val active: Boolean, val displayOrder: Int)

data class ProductFlavorRequest(
    val name: String,
    val description: String = "",
    @field:PositiveOrZero val priceCents: Long = 0,
)
data class ProductFlavorResponse(val id: UUID, val name: String, val description: String, val priceCents: Long, val active: Boolean, val displayOrder: Int)

// Preço da borda (CrustType) por produto. crustType = nome do enum CrustType.
data class ProductCrustPriceRequest(
    @field:NotBlank val crustType: String,
    @field:PositiveOrZero val priceCents: Long = 0,
)
data class ProductCrustPriceResponse(val id: UUID, val crustType: String, val priceCents: Long)

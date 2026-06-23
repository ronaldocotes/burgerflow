package com.menuflow.dto

import java.util.UUID

data class ProductSizeRequest(val name: String, val code: String, val priceCents: Long)
data class ProductSizeResponse(val id: UUID, val name: String, val code: String, val priceCents: Long, val active: Boolean, val displayOrder: Int)

data class ProductFlavorRequest(val name: String, val description: String = "")
data class ProductFlavorResponse(val id: UUID, val name: String, val description: String, val active: Boolean, val displayOrder: Int)

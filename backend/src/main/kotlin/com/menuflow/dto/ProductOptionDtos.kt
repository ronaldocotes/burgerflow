package com.menuflow.dto

import java.util.UUID

data class ProductOptionGroupRequest(
    val name: String,
    val minSelect: Int = 0,
    val maxSelect: Int = 1,
)

data class ProductOptionGroupResponse(
    val id: UUID,
    val name: String,
    val minSelect: Int,
    val maxSelect: Int,
    val required: Boolean,
    val active: Boolean,
    val displayOrder: Int,
    val options: List<ProductOptionResponse>,
)

data class ProductOptionRequest(
    val name: String,
    val priceCents: Long = 0,
)

data class ProductOptionResponse(
    val id: UUID,
    val name: String,
    val priceCents: Long,
    val active: Boolean,
    val displayOrder: Int,
)

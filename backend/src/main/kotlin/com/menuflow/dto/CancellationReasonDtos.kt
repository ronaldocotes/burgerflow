package com.menuflow.dto

import com.menuflow.model.CancellationReason
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class CancellationReasonResponse(
    val id: UUID?,
    val description: String,
    val active: Boolean,
    val sortOrder: Int,
) {
    companion object {
        fun from(r: CancellationReason) = CancellationReasonResponse(
            id = r.id,
            description = r.description,
            active = r.active,
            sortOrder = r.sortOrder,
        )
    }
}

data class CancellationReasonRequest(
    @field:NotBlank @field:Size(max = 140)
    val description: String,
    val active: Boolean = true,
    @field:Min(0)
    val sortOrder: Int = 0,
)

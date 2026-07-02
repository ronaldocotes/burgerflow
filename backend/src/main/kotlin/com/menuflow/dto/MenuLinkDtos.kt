package com.menuflow.dto

import com.menuflow.model.MenuLink
import com.menuflow.model.MenuLinkVariant
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class MenuLinkResponse(
    val id: UUID?,
    val slug: String,
    val variant: MenuLinkVariant,
    val label: String,
    val tableId: UUID?,
    val active: Boolean,
) {
    companion object {
        fun from(l: MenuLink) = MenuLinkResponse(
            id = l.id,
            slug = l.slug,
            variant = l.variant,
            label = l.label,
            tableId = l.tableId,
            active = l.active,
        )
    }
}

data class MenuLinkRequest(
    @field:NotBlank
    @field:Size(min = 2, max = 60)
    @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "slug deve ser minusculo, alfanumerico e com hifens")
    val slug: String,
    val variant: MenuLinkVariant,
    @field:NotBlank @field:Size(max = 80)
    val label: String,
    val tableId: UUID? = null,
    val active: Boolean = true,
)

/**
 * Resposta publica da resolucao de um link/QR (issue #11). Diz ao frontend qual
 * modo renderizar e se o pedido esta habilitado. NAO expoe dados internos.
 */
data class PublicMenuLinkResponse(
    val variant: MenuLinkVariant,
    /** false para VIEW_ONLY (vitrine sem pedido); true nos demais. */
    val orderingEnabled: Boolean,
    /** Mesa vinculada (COUNTER), se houver. */
    val tableId: UUID?,
)

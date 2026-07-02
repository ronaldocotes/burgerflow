package com.menuflow.dto

import jakarta.validation.constraints.Size
import java.util.UUID

/** Produto em destaque no pop-up, visao de gestao (inclui active p/ sinalizar item obsoleto). */
data class EntryPopupProductResponse(
    val productId: UUID,
    val name: String,
    val priceCents: Long,
    val effectivePriceCents: Long,
    val imageUrl: String?,
    /** false quando o produto foi desativado apos entrar no pop-up (dono deve trocar). */
    val active: Boolean,
    val sortOrder: Int,
)

/**
 * Estado do pop-up de entrada (issue #13), GET /config/entry-popup.
 * A lista vem na ordem de exibicao definida pelo dono.
 */
data class EntryPopupResponse(
    val enabled: Boolean,
    val title: String?,
    val products: List<EntryPopupProductResponse>,
)

/**
 * Substituicao atomica do pop-up (PUT /config/entry-popup). Semantica de replace:
 * a lista enviada VIRA o pop-up inteiro (nao e append). Guard-rail de ate 3
 * produtos validado no service; cada id deve ser de produto existente e ativo.
 */
data class EntryPopupUpdateRequest(
    val enabled: Boolean,
    @field:Size(max = 120)
    val title: String? = null,
    /** Ids dos produtos em destaque, na ordem desejada. Ate 3 (guard-rail no service). */
    val productIds: List<UUID> = emptyList(),
)

/** Pop-up exposto no cardapio publico (so quando enabled). Reusa o DTO publico do produto. */
data class PublicEntryPopupResponse(
    val enabled: Boolean,
    val title: String?,
    val products: List<PublicProductResponse>,
)

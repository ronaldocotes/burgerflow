package com.menuflow.service

import com.menuflow.dto.MenuLinkRequest
import com.menuflow.dto.MenuLinkResponse
import com.menuflow.dto.PublicMenuLinkResponse
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.MenuLink
import com.menuflow.model.MenuLinkVariant
import com.menuflow.repository.tenant.MenuLinkRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * CRUD das variantes de link/QR do cardapio (issue #11). A linha vive no banco do
 * proprio tenant (rota pela TenantContext), sem escopo cross-tenant a checar aqui.
 */
@Service
class MenuLinkService(
    private val repository: MenuLinkRepository,
) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(): List<MenuLinkResponse> =
        repository.findAllByOrderByCreatedAtAsc().map(MenuLinkResponse::from)

    @Transactional("tenantTransactionManager")
    fun create(req: MenuLinkRequest): MenuLinkResponse {
        requireCounterHasTable(req)
        require(!repository.existsBySlugAndActiveTrue(req.slug)) {
            "ja existe um link ativo com o slug '${req.slug}'"
        }
        val entity = MenuLink(
            slug = req.slug,
            variant = req.variant,
            label = req.label.trim(),
            tableId = req.tableId.takeIf { req.variant == MenuLinkVariant.COUNTER },
            active = req.active,
        )
        return MenuLinkResponse.from(repository.save(entity))
    }

    @Transactional("tenantTransactionManager")
    fun update(id: UUID, req: MenuLinkRequest): MenuLinkResponse {
        val entity = repository.findById(id)
            .orElseThrow { ResourceNotFoundException("Link de cardapio nao encontrado: $id") }
        requireCounterHasTable(req)
        // So checa colisao se o slug mudou (ou se reativando com slug ja usado).
        if ((req.slug != entity.slug || (req.active && !entity.active)) &&
            repository.existsBySlugAndActiveTrue(req.slug)
        ) {
            throw IllegalArgumentException("ja existe um link ativo com o slug '${req.slug}'")
        }
        entity.slug = req.slug
        entity.variant = req.variant
        entity.label = req.label.trim()
        entity.tableId = req.tableId.takeIf { req.variant == MenuLinkVariant.COUNTER }
        entity.active = req.active
        return MenuLinkResponse.from(repository.save(entity))
    }

    @Transactional("tenantTransactionManager")
    fun delete(id: UUID) {
        val entity = repository.findById(id)
            .orElseThrow { ResourceNotFoundException("Link de cardapio nao encontrado: $id") }
        repository.delete(entity)
    }

    /** Resolucao publica por slug (dentro do TenantContext ja setado pelo controller). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun resolvePublic(slug: String): PublicMenuLinkResponse {
        val link = repository.findBySlugAndActiveTrue(slug)
            ?: throw ResourceNotFoundException("Link de cardapio nao encontrado: $slug")
        return PublicMenuLinkResponse(
            variant = link.variant,
            orderingEnabled = link.variant != MenuLinkVariant.VIEW_ONLY,
            tableId = link.tableId,
        )
    }

    private fun requireCounterHasTable(req: MenuLinkRequest) {
        if (req.variant == MenuLinkVariant.COUNTER) {
            require(req.tableId != null) { "variant COUNTER exige tableId" }
        }
    }
}

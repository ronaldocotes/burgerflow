package com.menuflow.service

import com.menuflow.dto.EntryPopupProductResponse
import com.menuflow.dto.EntryPopupResponse
import com.menuflow.dto.EntryPopupUpdateRequest
import com.menuflow.dto.PublicEntryPopupResponse
import com.menuflow.dto.PublicProductResponse
import com.menuflow.model.EntryPopupProduct
import com.menuflow.model.Product
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.EntryPopupProductRepository
import com.menuflow.repository.tenant.ProductRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Pop-up de entrada com produtos em destaque (issue #13). Vive no banco do
 * TENANT (db-per-tenant), entao nao ha escopo cross-tenant a checar aqui: cada
 * conexao ja aterrissa no banco certo pela TenantContext.
 *
 * O toggle (enabled) e o titulo ficam na linha unica de tenant_config; os
 * produtos em destaque ficam em entry_popup_products. O PUT e uma SUBSTITUICAO
 * atomica: a lista enviada vira o pop-up inteiro. Guard-rail: ate 3 produtos.
 */
@Service
class EntryPopupService(
    private val popupRepository: EntryPopupProductRepository,
    private val productRepository: ProductRepository,
    private val tenantConfigRepository: TenantConfigRepository,
) {

    companion object {
        /** Guard-rail do issue #13: no maximo 3 destaques (evita poluicao visual). */
        const val MAX_FEATURED = 3
    }

    /** Visao de gestao: reflete o estado atual, inclusive produtos ja desativados. */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(): EntryPopupResponse {
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()
        val rows = popupRepository.findAllByOrderBySortOrderAsc()
        val byId = productRepository.findAllById(rows.map { it.productId }).associateBy { it.id }
        val products = rows.mapNotNull { row ->
            val p = byId[row.productId] ?: return@mapNotNull null
            EntryPopupProductResponse(
                productId = p.id!!,
                name = p.name,
                priceCents = p.priceCents,
                effectivePriceCents = p.effectivePriceCents(),
                imageUrl = p.imageUrl,
                active = p.active,
                sortOrder = row.sortOrder,
            )
        }
        return EntryPopupResponse(
            enabled = config.entryPopupEnabled,
            title = config.entryPopupTitle,
            products = products,
        )
    }

    /**
     * Pop-up para o cardapio PUBLICO. Quando desligado, devolve enabled=false e
     * lista vazia. Filtra produtos inativos (soft-delete) para nunca exibir item
     * fora do ar. Mapeia para o DTO publico (sem custo/SKU), sem grupos de opcao
     * (o pop-up e um teaser: clicar abre o produto no cardapio).
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun getForPublicMenu(config: TenantConfig?): PublicEntryPopupResponse {
        val enabled = config?.entryPopupEnabled ?: false
        if (!enabled) {
            return PublicEntryPopupResponse(enabled = false, title = config?.entryPopupTitle, products = emptyList())
        }
        val rows = popupRepository.findAllByOrderBySortOrderAsc()
        val byId = productRepository.findAllById(rows.map { it.productId }).associateBy { it.id }
        val products = rows.mapNotNull { row ->
            val p = byId[row.productId]
            if (p == null || !p.active) null else PublicProductResponse.from(p)
        }
        return PublicEntryPopupResponse(enabled = true, title = config.entryPopupTitle, products = products)
    }

    /**
     * Substitui o pop-up inteiro de forma atomica. Valida o guard-rail de 3, remove
     * duplicatas preservando a ordem, e exige que cada produto exista e esteja ativo.
     */
    @Transactional("tenantTransactionManager")
    fun update(req: EntryPopupUpdateRequest): EntryPopupResponse {
        val distinctIds = req.productIds.distinct()
        require(distinctIds.size <= MAX_FEATURED) {
            "o pop-up aceita no maximo $MAX_FEATURED produtos em destaque"
        }
        // Resolve e valida cada produto (existe + ativo). Ordem = ordem enviada.
        val resolved: List<Product> = distinctIds.map { id ->
            productRepository.findByIdAndActiveTrue(id)
                ?: throw IllegalArgumentException("produto invalido ou inativo no pop-up: $id")
        }

        // Substituicao: apaga os destaques atuais (bulk) e insere os novos.
        popupRepository.deleteAllInBatch()
        resolved.forEachIndexed { index, product ->
            popupRepository.save(EntryPopupProduct(productId = product.id!!, sortOrder = index))
        }

        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()
        config.entryPopupEnabled = req.enabled
        config.entryPopupTitle = req.title?.trim()?.ifBlank { null }
        tenantConfigRepository.save(config)

        return get()
    }
}

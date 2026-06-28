package com.menuflow.service

import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.PublicOptionGroupResponse
import com.menuflow.dto.PublicOptionResponse
import com.menuflow.dto.PublicProductResponse
import com.menuflow.dto.ProductResponse
import com.menuflow.dto.ProductUpdateRequest
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.Product
import com.menuflow.repository.tenant.ProductOptionGroupRepository
import com.menuflow.repository.tenant.ProductOptionRepository
import com.menuflow.repository.tenant.ProductRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val productOptionGroupRepository: ProductOptionGroupRepository,
    private val productOptionRepository: ProductOptionRepository,
    private val auditLogService: AuditLogService,
) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(pageable: Pageable): Page<ProductResponse> =
        productRepository.findByActiveTrue(pageable).map { ProductResponse.from(it) }

    /**
     * Lista para o cardapio PUBLICO: mesmo filtro (active=true), DTO sem campos sensiveis.
     * Carrega os grupos de complemento ativos em BATCH (sem N+1) e aninha no produto.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun listPublic(pageable: Pageable): Page<PublicProductResponse> {
        val products = productRepository.findByActiveTrue(pageable)
        if (products.isEmpty) return products.map { PublicProductResponse.from(it) }

        val productIds = products.content.mapNotNull { it.id }
        val groups = productOptionGroupRepository.findByProductIdInAndActiveTrue(productIds)
        if (groups.isEmpty()) return products.map { PublicProductResponse.from(it) }

        val groupIds = groups.mapNotNull { it.id }
        val optionsByGroup = productOptionRepository
            .findByGroupIdInAndActiveTrue(groupIds)
            .groupBy { it.groupId }
        val groupsByProduct = groups
            .sortedBy { it.displayOrder }
            .groupBy { it.productId }
            .mapValues { (_, gs) ->
                gs.map { g ->
                    PublicOptionGroupResponse(
                        id = g.id!!,
                        name = g.name,
                        minSelect = g.minSelect,
                        maxSelect = g.maxSelect,
                        required = g.minSelect >= 1,
                        options = (optionsByGroup[g.id].orEmpty())
                            .sortedBy { it.displayOrder }
                            .map { o -> PublicOptionResponse(o.id!!, o.name, o.priceCents) },
                    )
                }
            }

        return products.map { p -> PublicProductResponse.from(p, groupsByProduct[p.id].orEmpty()) }
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(id: UUID): ProductResponse =
        ProductResponse.from(getActiveEntity(id))

    @Transactional("tenantTransactionManager")
    fun create(req: ProductCreateRequest): ProductResponse {
        if (productRepository.existsBySku(req.sku)) {
            throw ConflictException("Product with SKU ${req.sku} already exists")
        }
        val product = Product(
            categoryId = req.categoryId,
            sku = req.sku,
            name = req.name,
            description = req.description,
            priceCents = req.priceCents,
            costPriceCents = req.costPriceCents,
            imageUrl = req.imageUrl,
            isAvailable = req.isAvailable,
            displayOrder = req.displayOrder,
            preparationTimeMinutes = req.preparationTimeMinutes,
            isFeatured = req.isFeatured,
            promoPriceCents = req.promoPriceCents,
            promoStartsAt = req.promoStartsAt,
            promoEndsAt = req.promoEndsAt,
        )
        val saved = productRepository.save(product)
        auditLogService.log("product.create", "product", saved.id, after = snapshot(saved))
        return ProductResponse.from(saved)
    }

    @Transactional("tenantTransactionManager")
    fun update(id: UUID, req: ProductUpdateRequest): ProductResponse {
        val product = getActiveEntity(id)
        if (product.sku != req.sku && productRepository.existsBySku(req.sku)) {
            throw ConflictException("Product with SKU ${req.sku} already exists")
        }
        val before = snapshot(product)
        product.categoryId = req.categoryId
        product.sku = req.sku
        product.name = req.name
        product.description = req.description
        product.priceCents = req.priceCents
        product.costPriceCents = req.costPriceCents
        product.imageUrl = req.imageUrl
        product.isAvailable = req.isAvailable
        product.displayOrder = req.displayOrder
        product.preparationTimeMinutes = req.preparationTimeMinutes
        product.isFeatured = req.isFeatured
        product.promoPriceCents = req.promoPriceCents
        product.promoStartsAt = req.promoStartsAt
        product.promoEndsAt = req.promoEndsAt
        val saved = productRepository.save(product)
        auditLogService.log("product.update", "product", saved.id, before = before, after = snapshot(saved))
        return ProductResponse.from(saved)
    }

    /** Soft delete: flip active=false, keep the row for order history integrity. */
    @Transactional("tenantTransactionManager")
    fun delete(id: UUID) {
        val product = getActiveEntity(id)
        val before = snapshot(product)
        product.active = false
        productRepository.save(product)
        auditLogService.log("product.delete", "product", id, before = before)
    }

    private fun getActiveEntity(id: UUID): Product =
        productRepository.findByIdAndActiveTrue(id)
            ?: throw ResourceNotFoundException("Product not found: $id")

    /** Snapshot enxuto p/ a trilha de auditoria (evita serializar a entidade JPA inteira). */
    private fun snapshot(p: Product): Map<String, Any?> = mapOf(
        "sku" to p.sku,
        "name" to p.name,
        "priceCents" to p.priceCents,
        "active" to p.active,
    )
}

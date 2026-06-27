package com.menuflow.service

import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.ProductResponse
import com.menuflow.dto.ProductUpdateRequest
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.Product
import com.menuflow.repository.tenant.ProductRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProductService(private val productRepository: ProductRepository) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(pageable: Pageable): Page<ProductResponse> =
        productRepository.findByActiveTrue(pageable).map { ProductResponse.from(it) }

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
        return ProductResponse.from(productRepository.save(product))
    }

    @Transactional("tenantTransactionManager")
    fun update(id: UUID, req: ProductUpdateRequest): ProductResponse {
        val product = getActiveEntity(id)
        if (product.sku != req.sku && productRepository.existsBySku(req.sku)) {
            throw ConflictException("Product with SKU ${req.sku} already exists")
        }
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
        return ProductResponse.from(productRepository.save(product))
    }

    /** Soft delete: flip active=false, keep the row for order history integrity. */
    @Transactional("tenantTransactionManager")
    fun delete(id: UUID) {
        val product = getActiveEntity(id)
        product.active = false
        productRepository.save(product)
    }

    private fun getActiveEntity(id: UUID): Product =
        productRepository.findByIdAndActiveTrue(id)
            ?: throw ResourceNotFoundException("Product not found: $id")
}

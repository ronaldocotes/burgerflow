package com.menuflow.service

import com.menuflow.dto.ProductFlavorRequest
import com.menuflow.dto.ProductFlavorResponse
import com.menuflow.model.ProductFlavor
import com.menuflow.repository.tenant.ProductFlavorRepository
import com.menuflow.repository.tenant.ProductRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProductFlavorService(
    private val repo: ProductFlavorRepository,
    private val productRepository: ProductRepository,
) {
    fun list(productId: UUID): List<ProductFlavorResponse> =
        repo.findByProductIdAndActiveTrue(productId).map { it.toResponse() }

    fun create(productId: UUID, req: ProductFlavorRequest): ProductFlavorResponse {
        productRepository.findById(productId).orElseThrow { IllegalArgumentException("Produto não encontrado") }
        return repo.save(ProductFlavor(productId = productId, name = req.name, description = req.description)).toResponse()
    }

    fun update(productId: UUID, flavorId: UUID, req: ProductFlavorRequest): ProductFlavorResponse {
        val flavor = repo.findById(flavorId).orElseThrow { IllegalArgumentException("Sabor não encontrado") }
        require(flavor.productId == productId) { "Sabor não pertence a este produto" }
        flavor.name = req.name; flavor.description = req.description
        return repo.save(flavor).toResponse()
    }

    fun delete(productId: UUID, flavorId: UUID) {
        val flavor = repo.findById(flavorId).orElseThrow { IllegalArgumentException("Sabor não encontrado") }
        require(flavor.productId == productId) { "Sabor não pertence a este produto" }
        flavor.active = false
        repo.save(flavor)
    }

    private fun ProductFlavor.toResponse() = ProductFlavorResponse(id!!, name, description, active, displayOrder)
}

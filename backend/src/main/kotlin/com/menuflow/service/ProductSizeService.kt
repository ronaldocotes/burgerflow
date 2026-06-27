package com.menuflow.service

import com.menuflow.dto.ProductSizeRequest
import com.menuflow.dto.ProductSizeResponse
import com.menuflow.model.ProductSize
import com.menuflow.repository.tenant.ProductRepository
import com.menuflow.repository.tenant.ProductSizeRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProductSizeService(
    private val repo: ProductSizeRepository,
    private val productRepository: ProductRepository,
) {
    fun list(productId: UUID): List<ProductSizeResponse> =
        repo.findByProductIdAndActiveTrue(productId).map { it.toResponse() }

    fun create(productId: UUID, req: ProductSizeRequest): ProductSizeResponse {
        productRepository.findById(productId).orElseThrow { IllegalArgumentException("Produto não encontrado") }
        require(req.priceCents > 0) { "Preço deve ser positivo" }
        require(!repo.existsByProductIdAndCode(productId, req.code)) { "Code '${req.code}' já existe neste produto" }
        return repo.save(ProductSize(productId = productId, name = req.name, code = req.code, priceCents = req.priceCents, promoPriceCents = req.promoPriceCents)).toResponse()
    }

    fun update(productId: UUID, sizeId: UUID, req: ProductSizeRequest): ProductSizeResponse {
        val size = repo.findById(sizeId).orElseThrow { IllegalArgumentException("Tamanho não encontrado") }
        require(size.productId == productId) { "Tamanho não pertence a este produto" }
        size.name = req.name; size.code = req.code; size.priceCents = req.priceCents
        size.promoPriceCents = req.promoPriceCents
        return repo.save(size).toResponse()
    }

    fun delete(productId: UUID, sizeId: UUID) {
        val size = repo.findById(sizeId).orElseThrow { IllegalArgumentException("Tamanho não encontrado") }
        require(size.productId == productId) { "Tamanho não pertence a este produto" }
        size.active = false
        repo.save(size)
    }

    private fun ProductSize.toResponse() = ProductSizeResponse(id!!, name, code, priceCents, promoPriceCents, active, displayOrder)
}

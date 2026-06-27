package com.menuflow.service

import com.menuflow.dto.ProductCrustPriceRequest
import com.menuflow.dto.ProductCrustPriceResponse
import com.menuflow.model.CrustType
import com.menuflow.model.ProductCrustPrice
import com.menuflow.repository.tenant.ProductCrustPriceRepository
import com.menuflow.repository.tenant.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProductCrustPriceService(
    private val repo: ProductCrustPriceRepository,
    private val productRepository: ProductRepository,
) {
    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(productId: UUID): List<ProductCrustPriceResponse> =
        repo.findByProductId(productId).map { it.toResponse() }

    /** Cria ou atualiza o preço de uma borda do produto (idempotente por (produto, borda)). */
    @Transactional("tenantTransactionManager")
    fun upsert(productId: UUID, req: ProductCrustPriceRequest): ProductCrustPriceResponse {
        productRepository.findById(productId).orElseThrow { IllegalArgumentException("Produto não encontrado") }
        require(req.priceCents >= 0) { "Preço não pode ser negativo" }
        val crust = parseCrust(req.crustType)
        val entity = repo.findByProductIdAndCrustType(productId, crust)?.apply { priceCents = req.priceCents }
            ?: ProductCrustPrice(productId = productId, crustType = crust, priceCents = req.priceCents)
        return repo.save(entity).toResponse()
    }

    @Transactional("tenantTransactionManager")
    fun delete(productId: UUID, crustType: String) {
        val crust = parseCrust(crustType)
        val existing = repo.findByProductIdAndCrustType(productId, crust) ?: return
        require(existing.productId == productId) { "Borda não pertence a este produto" }
        repo.delete(existing)
    }

    private fun parseCrust(raw: String): CrustType =
        enumValues<CrustType>().firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Borda inválida: $raw")

    private fun ProductCrustPrice.toResponse() = ProductCrustPriceResponse(id!!, crustType.name, priceCents)
}

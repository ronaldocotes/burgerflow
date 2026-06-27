package com.menuflow.service

import com.menuflow.dto.ProductOptionGroupRequest
import com.menuflow.dto.ProductOptionGroupResponse
import com.menuflow.dto.ProductOptionRequest
import com.menuflow.dto.ProductOptionResponse
import com.menuflow.model.ProductOption
import com.menuflow.model.ProductOptionGroup
import com.menuflow.repository.tenant.ProductOptionGroupRepository
import com.menuflow.repository.tenant.ProductOptionRepository
import com.menuflow.repository.tenant.ProductRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProductOptionGroupService(
    private val groupRepo: ProductOptionGroupRepository,
    private val optionRepo: ProductOptionRepository,
    private val productRepository: ProductRepository,
) {
    /** Grupos ativos do produto, cada um com suas opções ativas aninhadas. */
    fun list(productId: UUID): List<ProductOptionGroupResponse> {
        val groups = groupRepo.findByProductIdAndActiveTrue(productId)
        if (groups.isEmpty()) return emptyList()
        val optionsByGroup = optionRepo
            .findByGroupIdInAndActiveTrue(groups.mapNotNull { it.id })
            .groupBy { it.groupId }
        return groups
            .sortedBy { it.displayOrder }
            .map { it.toResponse(optionsByGroup[it.id].orEmpty()) }
    }

    fun createGroup(productId: UUID, req: ProductOptionGroupRequest): ProductOptionGroupResponse {
        productRepository.findById(productId)
            .orElseThrow { IllegalArgumentException("Produto não encontrado") }
        validateSelect(req.minSelect, req.maxSelect)
        val saved = groupRepo.save(
            ProductOptionGroup(
                productId = productId,
                name = req.name,
                minSelect = req.minSelect,
                maxSelect = req.maxSelect,
            ),
        )
        return saved.toResponse(emptyList())
    }

    fun updateGroup(productId: UUID, groupId: UUID, req: ProductOptionGroupRequest): ProductOptionGroupResponse {
        val group = loadGroup(productId, groupId)
        validateSelect(req.minSelect, req.maxSelect)
        group.name = req.name
        group.minSelect = req.minSelect
        group.maxSelect = req.maxSelect
        return groupRepo.save(group).toResponse(optionRepo.findByGroupIdAndActiveTrue(groupId))
    }

    fun deleteGroup(productId: UUID, groupId: UUID) {
        val group = loadGroup(productId, groupId)
        group.active = false
        groupRepo.save(group)
    }

    fun addOption(productId: UUID, groupId: UUID, req: ProductOptionRequest): ProductOptionResponse {
        loadGroup(productId, groupId)
        require(req.priceCents >= 0) { "Preço da opção não pode ser negativo" }
        return optionRepo.save(
            ProductOption(groupId = groupId, name = req.name, priceCents = req.priceCents),
        ).toResponse()
    }

    fun updateOption(productId: UUID, groupId: UUID, optionId: UUID, req: ProductOptionRequest): ProductOptionResponse {
        loadGroup(productId, groupId)
        val option = loadOption(groupId, optionId)
        require(req.priceCents >= 0) { "Preço da opção não pode ser negativo" }
        option.name = req.name
        option.priceCents = req.priceCents
        return optionRepo.save(option).toResponse()
    }

    fun deleteOption(productId: UUID, groupId: UUID, optionId: UUID) {
        loadGroup(productId, groupId)
        val option = loadOption(groupId, optionId)
        option.active = false
        optionRepo.save(option)
    }

    private fun loadGroup(productId: UUID, groupId: UUID): ProductOptionGroup {
        val group = groupRepo.findById(groupId)
            .orElseThrow { IllegalArgumentException("Grupo de opções não encontrado") }
        require(group.productId == productId) { "Grupo não pertence a este produto" }
        return group
    }

    private fun loadOption(groupId: UUID, optionId: UUID): ProductOption {
        val option = optionRepo.findById(optionId)
            .orElseThrow { IllegalArgumentException("Opção não encontrada") }
        require(option.groupId == groupId) { "Opção não pertence a este grupo" }
        return option
    }

    private fun validateSelect(min: Int, max: Int) {
        require(min >= 0) { "minSelect não pode ser negativo" }
        require(max >= 1) { "maxSelect deve ser ao menos 1" }
        require(min <= max) { "minSelect não pode ser maior que maxSelect" }
    }

    private fun ProductOptionGroup.toResponse(options: List<ProductOption>) = ProductOptionGroupResponse(
        id = id!!,
        name = name,
        minSelect = minSelect,
        maxSelect = maxSelect,
        required = minSelect >= 1,
        active = active,
        displayOrder = displayOrder,
        options = options.sortedBy { it.displayOrder }.map { it.toResponse() },
    )

    private fun ProductOption.toResponse() = ProductOptionResponse(id!!, name, priceCents, active, displayOrder)
}

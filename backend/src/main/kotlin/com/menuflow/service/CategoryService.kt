package com.menuflow.service

import com.menuflow.dto.CategoryRequest
import com.menuflow.dto.CategoryResponse
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.Category
import com.menuflow.repository.tenant.CategoryRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CategoryService(private val categoryRepository: CategoryRepository) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(pageable: Pageable): Page<CategoryResponse> =
        categoryRepository.findByActiveTrue(pageable).map { CategoryResponse.from(it) }

    @Transactional("tenantTransactionManager")
    fun create(req: CategoryRequest): CategoryResponse {
        if (categoryRepository.existsByName(req.name)) {
            throw ConflictException("Category '${req.name}' already exists")
        }
        val category = Category(
            name = req.name,
            description = req.description,
            displayOrder = req.displayOrder,
            colorCode = req.colorCode,
            iconUrl = req.iconUrl,
        )
        return CategoryResponse.from(categoryRepository.save(category))
    }

    @Transactional("tenantTransactionManager")
    fun update(id: UUID, req: CategoryRequest): CategoryResponse {
        val category = getActiveEntity(id)
        if (category.name != req.name && categoryRepository.existsByName(req.name)) {
            throw ConflictException("Category '${req.name}' already exists")
        }
        category.name = req.name
        category.description = req.description
        category.displayOrder = req.displayOrder
        category.colorCode = req.colorCode
        category.iconUrl = req.iconUrl
        return CategoryResponse.from(categoryRepository.save(category))
    }

    @Transactional("tenantTransactionManager")
    fun delete(id: UUID) {
        val category = getActiveEntity(id)
        category.active = false
        categoryRepository.save(category)
    }

    private fun getActiveEntity(id: UUID): Category =
        categoryRepository.findByIdAndActiveTrue(id)
            ?: throw ResourceNotFoundException("Category not found: $id")
}

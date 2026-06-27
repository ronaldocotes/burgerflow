package com.menuflow.service

import com.menuflow.dto.IngredientRequest
import com.menuflow.dto.IngredientResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.Ingredient
import com.menuflow.model.IngredientUnit
import com.menuflow.repository.tenant.IngredientRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class IngredientService(private val repo: IngredientRepository) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(): List<IngredientResponse> =
        repo.findByActiveTrueOrderByName().map { IngredientResponse.from(it) }

    @Transactional("tenantTransactionManager")
    fun create(req: IngredientRequest): IngredientResponse {
        if (repo.existsByName(req.name)) {
            throw BusinessException("Já existe um insumo com o nome '${req.name}'")
        }
        val ing = Ingredient(
            name = req.name,
            description = req.description,
            unit = parseUnit(req.unit),
            unitCostCents = req.unitCostCents,
            stockQuantity = req.stockQuantity,
            minStock = req.minStock,
            isAllergen = req.isAllergen,
        )
        return IngredientResponse.from(repo.save(ing))
    }

    @Transactional("tenantTransactionManager")
    fun update(id: UUID, req: IngredientRequest): IngredientResponse {
        val ing = repo.findById(id).orElseThrow { ResourceNotFoundException("Insumo não encontrado: $id") }
        ing.name = req.name
        ing.description = req.description
        ing.unit = parseUnit(req.unit)
        ing.unitCostCents = req.unitCostCents
        ing.stockQuantity = req.stockQuantity
        ing.minStock = req.minStock
        ing.isAllergen = req.isAllergen
        return IngredientResponse.from(repo.save(ing))
    }

    @Transactional("tenantTransactionManager")
    fun delete(id: UUID) {
        val ing = repo.findById(id).orElseThrow { ResourceNotFoundException("Insumo não encontrado: $id") }
        ing.active = false
        repo.save(ing)
    }

    private fun parseUnit(raw: String): IngredientUnit =
        runCatching { IngredientUnit.valueOf(raw.uppercase()) }
            .getOrElse { throw BusinessException("Unidade inválida: $raw") }
}

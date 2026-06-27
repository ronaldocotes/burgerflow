package com.menuflow.controller

import com.menuflow.dto.ProductCmvResponse
import com.menuflow.dto.RecipeItemRequest
import com.menuflow.dto.RecipeItemResponse
import com.menuflow.service.ProductRecipeService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Ficha técnica e CMV de um produto. context-path /api/v1 já aplicado. */
@RestController
@RequestMapping("/products/{productId}/recipe")
class ProductRecipeController(private val service: ProductRecipeService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','KITCHEN')")
    fun list(@PathVariable productId: UUID): List<RecipeItemResponse> = service.listRecipe(productId)

    @GetMapping("/cmv")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun cmv(@PathVariable productId: UUID): ProductCmvResponse = service.cmv(productId)

    @PutMapping("/{ingredientId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun upsert(
        @PathVariable productId: UUID,
        @PathVariable ingredientId: UUID,
        @Valid @RequestBody req: RecipeItemRequest,
    ): RecipeItemResponse = service.upsert(productId, ingredientId, req)

    @DeleteMapping("/{ingredientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun remove(@PathVariable productId: UUID, @PathVariable ingredientId: UUID) =
        service.remove(productId, ingredientId)
}

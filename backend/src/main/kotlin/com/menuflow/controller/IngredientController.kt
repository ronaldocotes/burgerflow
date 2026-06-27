package com.menuflow.controller

import com.menuflow.dto.IngredientRequest
import com.menuflow.dto.IngredientResponse
import com.menuflow.service.IngredientService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Insumos (estoque) do tenant. context-path /api/v1 ja aplicado -> mapping so /ingredients. */
@RestController
@RequestMapping("/ingredients")
class IngredientController(private val service: IngredientService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','KITCHEN')")
    fun list(): List<IngredientResponse> = service.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun create(@Valid @RequestBody req: IngredientRequest): IngredientResponse = service.create(req)

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody req: IngredientRequest): IngredientResponse =
        service.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun delete(@PathVariable id: UUID) = service.delete(id)
}

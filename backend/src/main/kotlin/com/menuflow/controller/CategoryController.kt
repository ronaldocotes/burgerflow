package com.menuflow.controller

import com.menuflow.dto.CategoryRequest
import com.menuflow.dto.CategoryResponse
import com.menuflow.service.CategoryService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/categories")
class CategoryController(private val categoryService: CategoryService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF','CASHIER','KITCHEN')")
    fun list(@PageableDefault(size = 50, sort = ["displayOrder"]) pageable: Pageable): Page<CategoryResponse> =
        categoryService.list(pageable)

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun create(@Valid @RequestBody req: CategoryRequest): ResponseEntity<CategoryResponse> {
        val created = categoryService.create(req)
        return ResponseEntity.created(URI.create("/api/v1/categories/${created.id}")).body(created)
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody req: CategoryRequest): CategoryResponse =
        categoryService.update(id, req)

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        categoryService.delete(id)
        return ResponseEntity.noContent().build()
    }
}

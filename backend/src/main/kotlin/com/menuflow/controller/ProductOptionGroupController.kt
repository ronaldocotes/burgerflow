package com.menuflow.controller

import com.menuflow.dto.ProductOptionGroupRequest
import com.menuflow.dto.ProductOptionGroupResponse
import com.menuflow.dto.ProductOptionRequest
import com.menuflow.dto.ProductOptionResponse
import com.menuflow.service.ProductOptionGroupService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Complementos (grupos de opções) de um produto. O context-path da app já é
 * /api/v1 (application.yml), então o mapping NÃO repete /api/v1 — apenas /products.
 */
@RestController
@RequestMapping("/products/{productId}/option-groups")
class ProductOptionGroupController(private val service: ProductOptionGroupService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','KITCHEN','DELIVERY')")
    fun list(@PathVariable productId: UUID): List<ProductOptionGroupResponse> = service.list(productId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun createGroup(@PathVariable productId: UUID, @Valid @RequestBody req: ProductOptionGroupRequest): ProductOptionGroupResponse =
        service.createGroup(productId, req)

    @PutMapping("/{groupId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun updateGroup(
        @PathVariable productId: UUID,
        @PathVariable groupId: UUID,
        @Valid @RequestBody req: ProductOptionGroupRequest,
    ): ProductOptionGroupResponse = service.updateGroup(productId, groupId, req)

    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun deleteGroup(@PathVariable productId: UUID, @PathVariable groupId: UUID) =
        service.deleteGroup(productId, groupId)

    @PostMapping("/{groupId}/options")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun addOption(
        @PathVariable productId: UUID,
        @PathVariable groupId: UUID,
        @Valid @RequestBody req: ProductOptionRequest,
    ): ProductOptionResponse = service.addOption(productId, groupId, req)

    @PutMapping("/{groupId}/options/{optionId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun updateOption(
        @PathVariable productId: UUID,
        @PathVariable groupId: UUID,
        @PathVariable optionId: UUID,
        @Valid @RequestBody req: ProductOptionRequest,
    ): ProductOptionResponse = service.updateOption(productId, groupId, optionId, req)

    @DeleteMapping("/{groupId}/options/{optionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun deleteOption(
        @PathVariable productId: UUID,
        @PathVariable groupId: UUID,
        @PathVariable optionId: UUID,
    ) = service.deleteOption(productId, groupId, optionId)
}

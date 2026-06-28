package com.menuflow.controller

import com.menuflow.dto.ActivateRequest
import com.menuflow.dto.ChangeRoleRequest
import com.menuflow.dto.InvitationResponse
import com.menuflow.dto.InviteRequest
import com.menuflow.dto.InviteResponse
import com.menuflow.dto.RevokeResponse
import com.menuflow.dto.UserResponse
import com.menuflow.service.UserManagementService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Gestão de usuários e convites do tenant (banco de CONTROLE). Sob o context-path
 * /api/v1. Leitura de usuários liberada a ADMIN/MANAGER; todas as mutações e a gestão
 * de convites são SÓ ADMIN. O escopo por tenant é garantido no service a partir do
 * principal assinado (nunca de header do cliente).
 */
@RestController
class UserManagementController(private val service: UserManagementService) {

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    fun listUsers(): List<UserResponse> = service.listUsers()

    @PostMapping("/users/invite")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    fun invite(@Valid @RequestBody req: InviteRequest): InviteResponse = service.invite(req)

    @PatchMapping("/users/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    fun changeRole(@PathVariable id: UUID, @Valid @RequestBody req: ChangeRoleRequest): UserResponse =
        service.changeRole(id, req)

    @PatchMapping("/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    fun setStatus(@PathVariable id: UUID, @Valid @RequestBody req: ActivateRequest): UserResponse =
        service.setStatus(id, req)

    @GetMapping("/invitations")
    @PreAuthorize("hasRole('ADMIN')")
    fun listInvitations(): List<InvitationResponse> = service.listPendingInvitations()

    @PostMapping("/invitations/{id}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    fun revoke(@PathVariable id: UUID): RevokeResponse = service.revokeInvitation(id)
}

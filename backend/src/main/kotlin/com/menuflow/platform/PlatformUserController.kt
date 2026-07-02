package com.menuflow.platform

import com.menuflow.dto.InviteResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.Valid
import java.util.UUID

/**
 * Gestao de usuarios de plataforma (SUPER_ADMINs) — Fase F3.
 *
 * GET    /admin/platform-users          — lista todos os SUPER_ADMINs do sistema
 * POST   /admin/platform-users/invite   — convida um novo SUPER_ADMIN
 * DELETE /admin/platform-users/{id}     — revoga o papel (rebaixa para ADMIN)
 *
 * O gate de SUPER_ADMIN e DUPLO: SecurityConfig ja bloqueia o prefixo /admin sem o papel,
 * e @PreAuthorize e a segunda camada (defesa em profundidade).
 */
@RestController
@RequestMapping("/admin/platform-users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
class PlatformUserController(
    private val platformUserService: PlatformUserService,
) {

    @GetMapping
    fun list(): ResponseEntity<List<PlatformUserSummary>> =
        ResponseEntity.ok(platformUserService.listSuperAdmins())

    @PostMapping("/invite")
    fun invite(@Valid @RequestBody request: InvitePlatformUserRequest): ResponseEntity<InviteResponse> =
        ResponseEntity.ok(platformUserService.inviteSuperAdmin(request.email))

    /**
     * Revoga o papel de SUPER_ADMIN do usuario alvo (rebaixa para ADMIN).
     * 409 se for o ultimo; 403 se auto-revogacao.
     */
    @DeleteMapping("/{id}")
    fun revoke(@PathVariable id: UUID): ResponseEntity<Void> {
        platformUserService.revokeSuperAdmin(id)
        return ResponseEntity.noContent().build()
    }
}

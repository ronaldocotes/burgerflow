package com.menuflow.controller

import com.menuflow.dto.AcceptInviteRequest
import com.menuflow.dto.TokenResponse
import com.menuflow.service.UserManagementService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Aceite de convite — PÚBLICO. Fica sob /auth (permitAll em SecurityConfig), como
 * login/refresh. Recebe token + nome + senha e devolve uma sessão para login imediato.
 */
@RestController
@RequestMapping("/auth")
class AcceptInviteController(private val service: UserManagementService) {

    @PostMapping("/accept-invite")
    fun accept(@Valid @RequestBody req: AcceptInviteRequest): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(service.acceptInvite(req))
}

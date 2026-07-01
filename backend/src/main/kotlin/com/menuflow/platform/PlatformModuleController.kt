package com.menuflow.platform

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Painel super-admin — entitlement de módulos por tenant. Rotas sob
 * /admin/tenants/{slug}/modules. Gate duplo (SecurityConfig + @PreAuthorize).
 *
 * GET lista TODOS os módulos com o status efetivo (override vs default do plano).
 * PUT faz o toggle otimista de um módulo e invalida o cache do gate na hora.
 */
@RestController
@RequestMapping("/admin/tenants/{slug}/modules")
@PreAuthorize("hasRole('SUPER_ADMIN')")
class PlatformModuleController(
    private val moduleGateService: ModuleGateService,
) {

    @GetMapping
    fun list(@PathVariable slug: String): List<ModuleStatusResponse> =
        moduleGateService.statusFor(slug)

    @PutMapping("/{moduleKey}")
    fun toggle(
        @PathVariable slug: String,
        @PathVariable moduleKey: String,
        @RequestBody req: ToggleModuleRequest,
    ): ModuleStatusResponse = moduleGateService.toggle(slug, moduleKey, req.enabled)
}

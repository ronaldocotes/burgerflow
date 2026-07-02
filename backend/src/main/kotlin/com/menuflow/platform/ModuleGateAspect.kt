package com.menuflow.platform

import com.menuflow.security.SecurityUtils
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Aspecto que intercepta métodos anotados com @RequiresModule e verifica, via
 * ModuleGateService (cache TTL 60s), se o módulo está habilitado para o tenant do
 * principal autenticado. Lança 403 se desabilitado — mesmo comportamento de
 * @PreAuthorize para o chamador.
 *
 * Precondição: o request já passou pelo JwtAuthFilter e tem um AuthPrincipal no
 * SecurityContext com tenantUuid preenchido. Se não houver principal (rota pública),
 * o aspecto deixa o Spring Security tratar (ele vai rejeitar antes de chegar aqui,
 * pois rotas públicas não devem ter @RequiresModule).
 *
 * Ordem de avaliação num handler típico de módulo opcional:
 *   1. JwtAuthFilter valida o token e popula o SecurityContext.
 *   2. @PreAuthorize verifica o papel mínimo.
 *   3. @RequiresModule (este aspecto) verifica o entitlement do tenant.
 *   4. Handler executa.
 *
 * AOP: usa @Before — se o módulo estiver desabilitado, o handler nunca executa.
 * spring-boot-starter-aop está no classpath (build.gradle.kts:28-29).
 */
@Aspect
@Component
class ModuleGateAspect(
    private val moduleGateService: ModuleGateService,
) {

    /**
     * Intercepta qualquer método anotado com @RequiresModule. O binding do
     * parâmetro [requiresModule] é feito pelo Spring AOP a partir da anotação
     * real presente no método, então [requiresModule.value] carrega o ModuleKey
     * correto sem reflexão manual.
     */
    @Before("@annotation(requiresModule)")
    fun checkModule(joinPoint: JoinPoint, requiresModule: RequiresModule) {
        val principal = SecurityUtils.currentPrincipalOrThrow()
        val enabled = moduleGateService.isEnabled(principal.tenantUuid, requiresModule.value)
        if (!enabled) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Módulo '${requiresModule.value.label}' não está habilitado para este tenant",
            )
        }
    }
}

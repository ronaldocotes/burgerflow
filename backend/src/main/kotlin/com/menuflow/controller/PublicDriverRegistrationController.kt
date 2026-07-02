package com.menuflow.controller

import com.menuflow.dto.DeliveryDriverPreviewResponse
import com.menuflow.dto.DriverRegistrationRequest
import com.menuflow.dto.DriverRegistrationResponse
import com.menuflow.repository.control.TenantRepository
import com.menuflow.service.DriverRegistrationService
import com.menuflow.tenant.TenantContext
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Fase C1 - auto-cadastro publico do motoboy freelancer.
 *
 * Sem autenticacao: o token (signup_token, UUID gerado no aceite do grupo, ver
 * DispatchService) funciona como "senha" do link enviado por WhatsApp. Coberto pelo
 * permitAll do path publico no SecurityConfig e pelo rate-limit por IP.
 *
 * O tenant vem do slug da URL: resolve com existsBySlug e vincula o TenantContext
 * ANTES de delegar ao service (as queries de tenant roteiam para o banco do
 * restaurante). try/finally garante o clear do ThreadLocal.
 */
@RestController
@RequestMapping("/public")
class PublicDriverRegistrationController(
    private val tenantRepository: TenantRepository,
    private val driverRegistrationService: DriverRegistrationService,
) {

    @GetMapping("/{tenantSlug}/motoboy/cadastro/{token}")
    fun preview(
        @PathVariable tenantSlug: String,
        @PathVariable token: UUID,
    ): ResponseEntity<DeliveryDriverPreviewResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            ResponseEntity.ok(driverRegistrationService.preview(token))
        } finally {
            TenantContext.clear()
        }
    }

    @PostMapping("/{tenantSlug}/motoboy/cadastro/{token}")
    fun complete(
        @PathVariable tenantSlug: String,
        @PathVariable token: UUID,
        @Valid @RequestBody req: DriverRegistrationRequest,
    ): ResponseEntity<DriverRegistrationResponse> {
        if (!tenantRepository.existsBySlug(tenantSlug)) return ResponseEntity.notFound().build()
        TenantContext.set(tenantSlug)
        return try {
            ResponseEntity.ok(driverRegistrationService.complete(token, req))
        } finally {
            TenantContext.clear()
        }
    }
}

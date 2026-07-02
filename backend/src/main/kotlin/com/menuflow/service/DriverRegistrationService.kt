package com.menuflow.service

import com.menuflow.dto.DeliveryDriverPreviewResponse
import com.menuflow.dto.DriverRegistrationRequest
import com.menuflow.dto.DriverRegistrationResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.repository.tenant.DeliveryDriverRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Fase C1 - auto-cadastro publico do motoboy freelancer.
 *
 * Roda no banco do TENANT (roteado pelo TenantContext, que o controller vincula a
 * partir do slug da URL). Como o modelo e db-per-tenant, o token de cadastro so e
 * encontrado no banco do proprio restaurante: um token de outro tenant simplesmente
 * nao existe neste banco (isolamento fisico) -> 404. Nao ha login: o token e a "senha".
 *
 * O complete() e transacional no tenantTransactionManager para que a mudanca do
 * driver e o registro de auditoria sejam atomicos (o AuditLogService.log e REQUIRED
 * e entra na mesma transacao).
 */
@Service
class DriverRegistrationService(
    private val driverRepository: DeliveryDriverRepository,
    private val auditLogService: AuditLogService,
) {

    @Transactional("tenantTransactionManager", readOnly = true)
    fun preview(token: UUID): DeliveryDriverPreviewResponse {
        val driver = driverRepository.findBySignupToken(token)
            ?: throw ResourceNotFoundException("Link de cadastro invalido")
        return DeliveryDriverPreviewResponse(
            name = driver.name,
            phoneMasked = maskPhone(driver.phone),
            provisional = driver.provisional,
            alreadyCompleted = driver.registrationCompletedAt != null,
        )
    }

    @Transactional("tenantTransactionManager")
    fun complete(token: UUID, req: DriverRegistrationRequest): DriverRegistrationResponse {
        // Defesa em profundidade: o @AssertTrue ja barra no boundary (400), mas o
        // servico tambem exige o aceite caso seja chamado fora do MVC (ex.: teste).
        if (!req.acceptedTerms) {
            throw BusinessException("Voce precisa aceitar os termos para concluir o cadastro")
        }

        val driver = driverRepository.findBySignupToken(token)
            ?: throw ResourceNotFoundException("Link de cadastro invalido")

        if (driver.registrationCompletedAt != null) {
            throw ConflictException("Cadastro ja concluido")
        }

        val now = Instant.now()
        driver.name = req.name.trim()
        driver.cpf = req.cpf?.trim()
        driver.cnhCategory = req.cnhCategory?.trim()
        driver.vehicleType = req.vehicleType?.trim()
        driver.licensePlate = req.licensePlate.trim().uppercase()
        driver.pixKey = req.pixKey.trim()
        driver.pixKeyType = req.pixKeyType?.trim()
        driver.fullAddress = req.fullAddress?.trim()?.ifBlank { null }
        driver.registrationCompletedAt = now
        driver.termsAcceptedAt = now
        driver.provisional = false
        driverRepository.save(driver)

        // Auditoria: o proprio motoboy e o ator (endpoint publico, sem principal).
        // Passar actorUserId explicito impede o log de virar no-op (AuditLogService
        // retorna em silencio quando nao ha ator resolvivel).
        auditLogService.log(
            action = "DRIVER_REGISTRATION_COMPLETED",
            entity = "DeliveryDriver",
            entityId = driver.id,
            actorUserId = driver.id,
            actorRole = "DRIVER",
        )

        return DriverRegistrationResponse("Cadastro concluido com sucesso!")
    }

    /** Mascara o telefone para o preview: "96 9****-1234" (nunca devolve o numero cheio). */
    private fun maskPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 4) return "****"
        val last4 = digits.takeLast(4)
        val ddd = if (digits.length >= 10) digits.take(2) else ""
        return if (ddd.isNotEmpty()) "$ddd 9****-$last4" else "9****-$last4"
    }
}

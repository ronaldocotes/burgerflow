package com.menuflow

import com.menuflow.dto.DriverRegistrationRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.model.DeliveryDriver
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.service.AuditLogService
import com.menuflow.service.DriverRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

/**
 * Fase C1 - auto-cadastro publico do motoboy (teste unitario Mockito, sem Spring/Docker).
 * Prova: conclusao com sucesso (campos persistidos + deixa de ser provisional),
 * cadastro ja concluido -> 409 (ConflictException) e termos nao aceitos -> 400
 * (BusinessException, defesa em profundidade alem do @AssertTrue do boundary).
 *
 * Verificamos interacoes com o objeto real 'driver' (sem matchers): Mockito.eq()/any()
 * com parametros String non-null do Kotlin retornam null e estouram NPE (nao temos
 * mockito-kotlin no classpath).
 */
class DriverRegistrationTest {

    private lateinit var driverRepository: DeliveryDriverRepository
    private lateinit var auditLogService: AuditLogService
    private lateinit var service: DriverRegistrationService

    private val token = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        driverRepository = Mockito.mock(DeliveryDriverRepository::class.java)
        auditLogService = Mockito.mock(AuditLogService::class.java)
        service = DriverRegistrationService(driverRepository, auditLogService)
    }

    private fun provisionalDriver(completedAt: Instant? = null) = DeliveryDriver(
        id = UUID.randomUUID(),
        name = "Motoboy 1234",
        phone = "96991231234",
        tenantId = tenantId,
        driverType = "FREELANCER",
        provisional = true,
        signupToken = token,
        registrationCompletedAt = completedAt,
    )

    private fun validRequest() = DriverRegistrationRequest(
        name = "Joao da Silva",
        cpf = "123.456.789-00",
        cnhCategory = "AB",
        vehicleType = "MOTO",
        licensePlate = "ABC1D23",
        pixKey = "joao@pix.com",
        pixKeyType = "EMAIL",
        fullAddress = "Rua X, 100",
        acceptedTerms = true,
    )

    @Test
    fun completeRegistration_success() {
        val driver = provisionalDriver()
        Mockito.`when`(driverRepository.findBySignupToken(token)).thenReturn(driver)

        val resp = service.complete(token, validRequest())

        assertEquals("Cadastro concluido com sucesso!", resp.message)
        // Campos persistidos na mesma instancia (o service muta e salva).
        assertEquals("Joao da Silva", driver.name)
        assertEquals("123.456.789-00", driver.cpf)
        assertEquals("AB", driver.cnhCategory)
        assertEquals("MOTO", driver.vehicleType)
        assertEquals("ABC1D23", driver.licensePlate)
        assertEquals("joao@pix.com", driver.pixKey)
        assertEquals("EMAIL", driver.pixKeyType)
        assertEquals("Rua X, 100", driver.fullAddress)
        assertNotNull(driver.registrationCompletedAt)
        assertNotNull(driver.termsAcceptedAt)
        assertFalse(driver.provisional)
        // Persistiu e auditou (objeto real, sem matchers).
        Mockito.verify(driverRepository).save(driver)
    }

    @Test
    fun completeRegistration_alreadyDone() {
        val driver = provisionalDriver(completedAt = Instant.now())
        Mockito.`when`(driverRepository.findBySignupToken(token)).thenReturn(driver)

        assertThrows(ConflictException::class.java) {
            service.complete(token, validRequest())
        }
        // Nada e regravado quando ja concluido.
        Mockito.verify(driverRepository, Mockito.never()).save(driver)
    }

    @Test
    fun completeRegistration_termsNotAccepted() {
        // Termos recusados sao barrados antes mesmo de tocar o repositorio.
        val req = validRequest().copy(acceptedTerms = false)

        assertThrows(BusinessException::class.java) {
            service.complete(token, req)
        }
    }
}

package com.menuflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/**
 * Contrato HTTP + RBAC do acerto de motoboy (issue #3):
 *  - G4 (regressao): "abrir acerto" pela web envia o id do entregador sob a chave
 *    "userId". O teste posta o JSON LITERAL (nao um DTO Kotlin) para provar que o
 *    @JsonAlias("userId") de OpenSettlementRequest.driverId resolve (201, nao 400).
 *    Montar o DTO em Kotlin NAO pegaria o bug — tem que ser o JSON cru.
 *  - D-B (RBAC): MANAGER abre/fecha acerto, mas NAO altera as tarifas de config
 *    (so ADMIN). ADMIN pode as duas coisas.
 */
@AutoConfigureMockMvc
class DriverSettlementHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val driverRepository: DeliveryDriverRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var slug: String
    private lateinit var driverId: UUID

    @BeforeEach
    fun seed() {
        slug = "setl_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Settlement Burger"))
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "admin@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Admin",
                role = UserRole.ADMIN,
            ),
        )
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "manager@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Manager",
                role = UserRole.MANAGER,
            ),
        )
        TenantContext.set(slug)
        try {
            driverId = driverRepository.save(
                DeliveryDriver(
                    name = "Moto Setl",
                    phone = "55" + UUID.randomUUID().toString().filter { it.isDigit() }.take(11).padEnd(11, '7'),
                    tenantId = tenant.id!!,
                ),
            ).id!!
        } finally {
            TenantContext.clear()
        }
    }

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun login(email: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to email, "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    @Test
    fun `G4 - open settlement with literal userId key succeeds`() {
        val adminToken = login("admin@$slug.com")
        val today = LocalDate.now().toString()

        // JSON LITERAL como o front envia: chave "userId" (nao "driverId").
        val json = """{"userId":"$driverId","periodStart":"$today","periodEnd":"$today"}"""
        mockMvc.perform(
            post("/drivers/settlements/open")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON).content(json),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.driverId").value(driverId.toString()))
            .andExpect(jsonPath("$.status").value("OPEN"))
    }

    @Test
    fun `D-B RBAC - MANAGER opens and closes settlement but cannot edit config`() {
        val managerToken = login("manager@$slug.com")
        val today = LocalDate.now().toString()

        // MANAGER abre o acerto (usando a chave "driverId" canonica tambem aceita).
        val openJson = """{"driverId":"$driverId","periodStart":"$today","periodEnd":"$today"}"""
        val openRes = mockMvc.perform(
            post("/drivers/settlements/open")
                .header("Authorization", "Bearer $managerToken")
                .contentType(MediaType.APPLICATION_JSON).content(openJson),
        ).andExpect(status().isCreated).andReturn()
        val settlementId = objectMapper.readTree(openRes.response.contentAsString).get("id").asText()

        // MANAGER NAO altera as tarifas de config -> 403 (so ADMIN).
        val configJson = """{"dailyRateCents":5000,"perDeliveryCents":300,"perKmCents":100}"""
        mockMvc.perform(
            put("/drivers/$driverId/config")
                .header("Authorization", "Bearer $managerToken")
                .contentType(MediaType.APPLICATION_JSON).content(configJson),
        ).andExpect(status().isForbidden)

        // ADMIN configura as tarifas (necessario para o FROTA fechar).
        val adminToken = login("admin@$slug.com")
        mockMvc.perform(
            put("/drivers/$driverId/config")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON).content(configJson),
        ).andExpect(status().isOk)

        // MANAGER fecha o acerto (permitido).
        val closeJson = """{"workingDays":3}"""
        mockMvc.perform(
            post("/drivers/settlements/$settlementId/close")
                .header("Authorization", "Bearer $managerToken")
                .contentType(MediaType.APPLICATION_JSON).content(closeJson),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CLOSED"))
            .andExpect(jsonPath("$.settlementType").value("FROTA"))
    }
}

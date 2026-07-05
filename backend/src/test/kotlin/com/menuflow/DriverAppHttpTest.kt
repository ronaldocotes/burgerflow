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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Fase 6.2 — RBAC HTTP do app do motoboy (JWT real via /auth/login):
 *  - M1 (auditoria): DRIVER nao le a fila do tenant inteiro (GET /delivery/orders/active)
 *    nem a frota com GPS (GET /delivery/drivers) -> 403; ADMIN segue lendo;
 *  - o vinculo user<->driver e so de gestao (PUT /delivery/drivers/{id}/user):
 *    DRIVER -> 403; ADMIN vincula e o app passa a resolver GET /delivery/me;
 *  - POST /delivery/shift liga o turno do proprio motoboy;
 *  - GET /delivery/earnings/my responde ao DRIVER (sem driverId na query);
 *  - POST /delivery/orders/{id}/status esta mapeado (espelho do PUT p/ o app).
 */
@AutoConfigureMockMvc
class DriverAppHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val driverRepository: DeliveryDriverRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    private lateinit var slug: String
    private lateinit var driverId: UUID
    private lateinit var driverUserId: UUID

    @BeforeEach
    fun seed() {
        slug = "drvhttp_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "Drv Http Burger"))
        val admin = userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "admin@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Admin",
                role = UserRole.ADMIN,
            ),
        )
        check(admin.id != null)
        val driverUser = userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "moto@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Moto",
                role = UserRole.DRIVER,
            ),
        )
        driverUserId = driverUser.id!!

        // Entregador no banco do TENANT (provisionado sob demanda pelo routing DS).
        TenantContext.set(slug)
        try {
            driverId = driverRepository.save(
                DeliveryDriver(
                    name = "Moto Http",
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
    fun `driver app RBAC end to end`() {
        val adminToken = login("admin@$slug.com")
        val driverToken = login("moto@$slug.com")

        // M1: fila do tenant inteiro e frota com GPS sao de gestao -> DRIVER 403.
        mockMvc.perform(get("/delivery/orders/active").header("Authorization", "Bearer $driverToken"))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/delivery/drivers").header("Authorization", "Bearer $driverToken"))
            .andExpect(status().isForbidden)
        // ADMIN segue lendo.
        mockMvc.perform(get("/delivery/orders/active").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk)
        mockMvc.perform(get("/delivery/drivers").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk)

        // Sem vinculo ainda: o app nao resolve o motoboy -> 403.
        mockMvc.perform(get("/delivery/me").header("Authorization", "Bearer $driverToken"))
            .andExpect(status().isForbidden)

        // DRIVER nao vincula ninguem (endpoint de gestao) -> 403.
        val linkBody = objectMapper.writeValueAsString(mapOf("userId" to driverUserId.toString()))
        mockMvc.perform(
            put("/delivery/drivers/$driverId/user")
                .header("Authorization", "Bearer $driverToken")
                .contentType(MediaType.APPLICATION_JSON).content(linkBody),
        ).andExpect(status().isForbidden)

        // ADMIN vincula o user DRIVER ao entregador.
        mockMvc.perform(
            put("/delivery/drivers/$driverId/user")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON).content(linkBody),
        ).andExpect(status().isOk)

        // Agora o app resolve o proprio perfil.
        val meRes = mockMvc.perform(get("/delivery/me").header("Authorization", "Bearer $driverToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Moto Http"))
            .andReturn()
        assertEquals(
            driverId.toString(),
            objectMapper.readTree(meRes.response.contentAsString).get("id").asText(),
        )

        // Liga o turno proprio (sem id na rota).
        val shiftBody = objectMapper.writeValueAsString(mapOf("activeShift" to true))
        mockMvc.perform(
            post("/delivery/shift")
                .header("Authorization", "Bearer $driverToken")
                .contentType(MediaType.APPLICATION_JSON).content(shiftBody),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.activeShift").value(true))

        // Ganhos do proprio motoboy (sem driverId na query; sem config -> zeros).
        mockMvc.perform(get("/delivery/earnings/my").header("Authorization", "Bearer $driverToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deliveriesCount").value(0))
            .andExpect(jsonPath("$.hasConfig").value(false))

        // Ofertas pendentes do proprio motoboy (vazio, mas 200 — rota existe p/ o app).
        mockMvc.perform(get("/delivery/offers/my").header("Authorization", "Bearer $driverToken"))
            .andExpect(status().isOk)

        // POST espelho do status esta mapeado: pedido inexistente -> 404 (nao 405).
        val statusBody = objectMapper.writeValueAsString(mapOf("deliveryStatus" to "DELIVERED"))
        mockMvc.perform(
            post("/delivery/orders/${UUID.randomUUID()}/status")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON).content(statusBody),
        ).andExpect(status().isNotFound)
    }
}

package com.menuflow.platform

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.IntegrationTestBase
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.AppReleaseRepository
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Contrato do AppReleaseController (distribuicao do APK do app do motoboy), banco de
 * CONTROLE real (Testcontainers). Cobre:
 *  - publicar (SUPER_ADMIN) grava e o /public/app/latest reflete;
 *  - /latest sempre reflete o MAIOR version_code e NUNCA inclui o binario;
 *  - nao-SUPER_ADMIN -> 403; sem token -> 401;
 *  - arquivo sem o magic "PK" -> 400;
 *  - /latest 204 quando nao ha release;
 *  - download devolve os bytes exatos + Content-Type de APK + Content-Disposition;
 *  - versionCode duplicado -> 409 (decisao: nao sobrescreve release ja publicado).
 */
@AutoConfigureMockMvc
class AppReleaseControllerIT @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val releaseRepository: AppReleaseRepository,
) : IntegrationTestBase() {

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        releaseRepository.deleteAll()
        slug = "apprel${UUID.randomUUID().toString().replace("-", "").take(9)}"
        tenantRepository.save(Tenant(slug = slug, displayName = "AppRelease Burger"))
    }

    private fun addUser(role: UserRole): String {
        val email = "${role.name.lowercase()}@$slug.com"
        val tenant = tenantRepository.findBySlug(slug)!!
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = email,
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "U",
                role = role,
            ),
        )
        return email
    }

    private fun login(email: String): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to email, "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    private fun superAdminToken() = login(addUser(UserRole.SUPER_ADMIN))

    /** Bytes que comecam com o magic "PK" (todo APK e um zip), do tamanho pedido. */
    private fun apkFalso(kib: Int): ByteArray {
        val b = ByteArray(kib * 1024)
        b[0] = 'P'.code.toByte(); b[1] = 'K'.code.toByte(); b[2] = 3; b[3] = 4
        // Preenche o resto com um padrao nao-zero para diferenciar versoes no download.
        for (i in 4 until b.size) b[i] = ((i * 31) % 251).toByte()
        return b
    }

    private fun publish(
        token: String,
        code: Int,
        name: String,
        apk: ByteArray,
        obrigatoria: Boolean = false,
        notas: String? = null,
    ) = mockMvc.perform(
        post("/admin/app/releases")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .param("versionCode", code.toString())
            .param("versionName", name)
            .param("obrigatoria", obrigatoria.toString())
            .apply { if (notas != null) param("notas", notas) }
            .content(apk),
    )

    // ── publicar + latest ──────────────────────────────────────────────────────

    @Test
    fun `publicar como SUPER_ADMIN grava e o latest reflete`() {
        val token = superAdminToken()
        publish(token, 2, "1.1.0", apkFalso(8), obrigatoria = false, notas = "Novidades da 1.1.0")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.versionCode").value(2))
            .andExpect(jsonPath("$.sha256").isNotEmpty)

        mockMvc.perform(get("/public/app/latest"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.versionCode").value(2))
            .andExpect(jsonPath("$.versionName").value("1.1.0"))
            .andExpect(jsonPath("$.notas").value("Novidades da 1.1.0"))
            .andExpect(jsonPath("$.obrigatoria").value(false))
            .andExpect(jsonPath("$.tamanhoBytes").value(8 * 1024))
            .andExpect(jsonPath("$.sha256").isNotEmpty)
            .andExpect(jsonPath("$.url").value("/api/v1/public/app/download/2?plataforma=android"))
    }

    @Test
    fun `latest reflete sempre o maior versionCode e nao inclui o binario`() {
        val token = superAdminToken()
        publish(token, 2, "1.1.0", apkFalso(4)).andExpect(status().isOk)
        publish(token, 5, "1.4.0", apkFalso(4), obrigatoria = true).andExpect(status().isOk)
        publish(token, 3, "1.2.0", apkFalso(4)).andExpect(status().isOk)

        val res = mockMvc.perform(get("/public/app/latest"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.versionCode").value(5))
            .andExpect(jsonPath("$.obrigatoria").value(true))
            .andReturn()
        // O binario NUNCA aparece no /latest (sem campo apk/apkBytes/bytes).
        val body = res.response.contentAsString
        assert(!body.contains("apkBytes") && !body.contains("\"apk\"")) { "latest nao pode expor o binario" }
    }

    @Test
    fun `latest sem nenhuma versao publicada retorna 204`() {
        mockMvc.perform(get("/public/app/latest")).andExpect(status().isNoContent)
    }

    // ── download ────────────────────────────────────────────────────────────────

    @Test
    fun `download devolve os bytes exatos com content-type de APK`() {
        val token = superAdminToken()
        val apk = apkFalso(16)
        publish(token, 2, "1.1.0", apk).andExpect(status().isOk)

        val res = mockMvc.perform(get("/public/app/download/2"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/vnd.android.package-archive"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("menuflow-motoboy-1.1.0.apk")))
            .andReturn()
        assertArrayEquals(apk, res.response.contentAsByteArray)
    }

    @Test
    fun `download de versao inexistente retorna 404`() {
        mockMvc.perform(get("/public/app/download/999")).andExpect(status().isNotFound)
    }

    @Test
    fun `download traz Cache-Control imutavel de 1 ano e ETag = sha256`() {
        val token = superAdminToken()
        publish(token, 2, "1.1.0", apkFalso(8)).andExpect(status().isOk)
        // O sha256 gravado vira o ETag (entre aspas).
        val sha = releaseRepository.findFirstByPlataformaAndVersionCode("android", 2)!!.sha256

        mockMvc.perform(get("/public/app/download/2"))
            .andExpect(status().isOk)
            // APK de um versionCode e imutavel -> pode cachear forte (1 ano) sem revalidar.
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=31536000")))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")))
            .andExpect(header().string("ETag", "\"$sha\""))
    }

    // Obs.: o guarda de tamanho do upload (A2) e a leitura em stream com teto — nao da
    // para forjar Content-Length no MockHttpServletRequest (getContentLengthLong deriva
    // do proprio content). Ele e testado, deterministico e rapido, com um cap pequeno em
    // [AppReleaseControllerBoundedReadTest]. Aqui garantimos so que um upload normal (bem
    // abaixo do teto) continua passando fim-a-fim apos a mudanca de @RequestBody -> stream.

    // ── RBAC ─────────────────────────────────────────────────────────────────────

    @Test
    fun `publicar sem token retorna 401`() {
        mockMvc.perform(
            post("/admin/app/releases")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .param("versionCode", "2").param("versionName", "1.1.0")
                .content(apkFalso(4)),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `publicar como ADMIN comum (nao SUPER_ADMIN) retorna 403`() {
        val token = login(addUser(UserRole.ADMIN))
        publish(token, 2, "1.1.0", apkFalso(4)).andExpect(status().isForbidden)
    }

    // ── validacao ────────────────────────────────────────────────────────────────

    @Test
    fun `publicar arquivo sem o magic PK retorna 400`() {
        val token = superAdminToken()
        val naoApk = ByteArray(4096) // zeros, sem "PK"
        publish(token, 2, "1.1.0", naoApk).andExpect(status().isBadRequest)
    }

    @Test
    fun `publicar versionCode duplicado retorna 409`() {
        val token = superAdminToken()
        publish(token, 2, "1.1.0", apkFalso(4)).andExpect(status().isOk)
        publish(token, 2, "1.1.0b", apkFalso(4)).andExpect(status().isConflict)
        // Continua havendo exatamente 1 release (o primeiro nao foi sobrescrito).
        assertEquals(1, releaseRepository.count())
    }
}

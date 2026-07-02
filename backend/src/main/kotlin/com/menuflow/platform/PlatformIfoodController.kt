package com.menuflow.platform

import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.control.IfoodAppConfig
import com.menuflow.repository.control.IfoodAppConfigRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Painel super-admin — gerenciamento das credenciais de aplicação iFood.
 * Rotas sob /admin/ifood-apps (banco de CONTROLE, nível plataforma).
 *
 * Segurança write-only:
 *  - clientSecret NUNCA é retornado: a resposta expõe apenas os últimos 4 chars
 *    do segredo decifrado (secretLast4), suficiente para o super-admin confirmar
 *    qual credencial está ativa sem vazar o valor completo.
 *  - PUT /admin/ifood-apps/{id} serve para rotação: incrementa keyVersion para
 *    rastrear qual versão da chave mestra AES foi usada na cifragem.
 *
 * Gate DUPLO: path-level em SecurityConfig + @PreAuthorize aqui.
 */
@RestController
@RequestMapping("/admin/ifood-apps")
@PreAuthorize("hasRole('SUPER_ADMIN')")
class PlatformIfoodController(
    private val repo: IfoodAppConfigRepository,
    private val cipher: IfoodTokenCipher,
) {

    @GetMapping
    fun list(): List<IfoodAppSummaryResponse> =
        repo.findAll().map { it.toSummary() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateIfoodAppRequest): IfoodAppSummaryResponse {
        val (secretEnc, secretIv) = cipher.encrypt(req.clientSecret)
        return repo.save(
            IfoodAppConfig(
                role = req.role,
                clientId = req.clientId.trim(),
                clientSecretEnc = secretEnc,
                clientSecretIv = secretIv,
                cnpj = req.cnpj.trim(),
                active = req.active,
            ),
        ).toSummary()
    }

    /**
     * Rotação do clientSecret. Incrementa keyVersion para indicar que a chave
     * AES vigente (IFOOD_ENCRYPTION_KEY) foi usada na reencifragem.
     */
    @PutMapping("/{id}")
    fun rotate(
        @PathVariable id: UUID,
        @Valid @RequestBody req: RotateIfoodSecretRequest,
    ): IfoodAppSummaryResponse {
        val app = repo.findById(id)
            .orElseThrow { ResourceNotFoundException("IfoodAppConfig não encontrado: $id") }
        val (secretEnc, secretIv) = cipher.encrypt(req.clientSecret)
        app.clientSecretEnc = secretEnc
        app.clientSecretIv = secretIv
        app.keyVersion++
        return repo.save(app).toSummary()
    }

    // ── Mapeamento (decifra no servidor, expõe só last4) ────────────────────

    private fun IfoodAppConfig.toSummary(): IfoodAppSummaryResponse {
        val plain = try { cipher.decrypt(clientSecretEnc, clientSecretIv) } catch (_: Exception) { "" }
        val last4 = if (plain.length >= 4) plain.takeLast(4) else "****"
        return IfoodAppSummaryResponse(
            id = id!!,
            role = role,
            clientId = clientId,
            secretLast4 = last4,
            cnpj = cnpj,
            active = active,
            createdAt = createdAt,
        )
    }
}

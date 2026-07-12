package com.menuflow.platform

import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Painel super-admin — gerenciamento das chaves de API da plataforma (Google Maps
 * etc.). Rotas sob /admin/api-keys (banco de CONTROLE, nivel plataforma).
 *
 * Seguranca:
 *  - Gate DUPLO: path-level em SecurityConfig (todo /admin exige SUPER_ADMIN) +
 *    @PreAuthorize aqui (suspensorio; um controller sob /admin sem anotacao ainda cai
 *    no matcher path-level).
 *  - WRITE-ONLY: nenhuma resposta devolve o valor da chave — so status/source/masked
 *    (4+4 chars). O valor cifrado (value_enc) nunca e serializado.
 *  - Toda mutacao passa por [PlatformApiKeyService] (cifra + audit mascarada + invalidate).
 */
@RestController
@RequestMapping("/admin/api-keys")
@PreAuthorize("hasRole('SUPER_ADMIN')")
class PlatformApiKeyController(
    private val service: PlatformApiKeyService,
) {

    /** Lista o estado (mascarado) de todos os provedores conhecidos. */
    @GetMapping
    fun list(): List<PlatformApiKeyResponse> = service.describeAll()

    /** Estado (mascarado) de UM provedor. Provider desconhecido -> 400. */
    @GetMapping("/{provider}")
    fun get(@PathVariable provider: String): PlatformApiKeyResponse =
        service.describe(service.parseProvider(provider))

    /** Upsert/rotacao da chave. Provider desconhecido -> 400; value invalido -> 422. */
    @PutMapping("/{provider}")
    fun put(
        @PathVariable provider: String,
        @Valid @RequestBody req: PutPlatformApiKeyRequest,
    ): PlatformApiKeyResponse =
        service.upsert(service.parseProvider(provider), req.value)

    /** Desativa a chave ativa (volta ao fallback env). Provider desconhecido -> 400. */
    @DeleteMapping("/{provider}")
    fun delete(@PathVariable provider: String): PlatformApiKeyResponse =
        service.deactivate(service.parseProvider(provider))

    /** Teste de amostra (um geocode) com a chave vigente. Rate-limit por ator (429). */
    @PostMapping("/{provider}/test")
    fun test(@PathVariable provider: String): PlatformApiKeyTestResponse =
        service.test(service.parseProvider(provider))
}

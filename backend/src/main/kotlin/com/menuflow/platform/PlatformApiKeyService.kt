package com.menuflow.platform

import com.menuflow.crypto.SecretCipher
import com.menuflow.dispatch.GeocodingService
import com.menuflow.dispatch.GoogleApiKeyProvider
import com.menuflow.dispatch.GoogleKeySource
import com.menuflow.exception.BusinessException
import com.menuflow.exception.TooManyRequestsException
import com.menuflow.exception.UnprocessableEntityException
import com.menuflow.model.control.PlatformApiKey
import com.menuflow.model.control.PlatformApiKeyProviderType
import com.menuflow.repository.control.PlatformApiKeyRepository
import com.menuflow.security.SecurityUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Regras de negocio das chaves de API da plataforma (banco de CONTROLE, nivel
 * plataforma). Toda mutacao:
 *  - cifra o valor com [SecretCipher] (AES-256-GCM) — o texto claro nunca e persistido;
 *  - respeita 1 chave ATIVA por provedor (indice unico parcial, V16): a rotacao ATUALIZA
 *    a linha ativa in-place incrementando key_version (nao cria duplicata);
 *  - invalida o cache do [GoogleApiKeyProvider] para a nova valer na hora;
 *  - grava trilha de auditoria MASCARADA (nunca o valor).
 *
 * WRITE-ONLY: nenhuma leitura devolve o valor — so status/source/masked (4+4 chars).
 */
@Service
class PlatformApiKeyService(
    private val repository: PlatformApiKeyRepository,
    private val cipher: SecretCipher,
    private val googleApiKeyProvider: GoogleApiKeyProvider,
    private val geocodingService: GeocodingService,
    private val auditService: PlatformAuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Rate-limit do endpoint /test por ATOR: no maximo 1 chamada a cada 5s, para nao
    // torrar a cota do Google. TTL manual (carimbo de ultima chamada por userId).
    private val testLastCallByActor = ConcurrentHashMap<UUID, Long>()
    private val testMinIntervalMs = 5_000L

    // ── Leitura (write-only) ────────────────────────────────────────────────

    /** Todos os provedores conhecidos (allowlist do enum), descritos sem o valor. */
    fun describeAll(): List<PlatformApiKeyResponse> =
        PlatformApiKeyProviderType.entries.map { describe(it) }

    fun describe(provider: PlatformApiKeyProviderType): PlatformApiKeyResponse {
        val row = repository.findFirstByProviderAndActiveTrue(provider)
        // source()/resolve() do provider dizem de onde a chave viria AGORA (DB > ENV > NONE)
        // e permitem mascarar o valor vigente sem devolve-lo.
        val source = googleApiKeyProvider.source()
        val resolved = googleApiKeyProvider.resolve()
        val status = if (source == GoogleKeySource.NONE) "ABSENT" else "DEFINED"
        return PlatformApiKeyResponse(
            provider = provider.name,
            status = status,
            masked = if (status == "DEFINED") mask(resolved) else null,
            source = source.name,
            keyVersion = row?.keyVersion,
            updatedAt = row?.updatedAt,
            updatedBy = row?.updatedBy,
        )
    }

    // ── Mutacao ──────────────────────────────────────────────────────────────

    /**
     * Upsert/rotacao da chave ativa de [provider]. Se ja existe linha ativa, ATUALIZA-a
     * (mesma linha, key_version++); senao cria uma nova (key_version=1). Invalida o cache
     * e audita com before/after MASCARADOS.
     */
    @Transactional
    fun upsert(provider: PlatformApiKeyProviderType, rawValue: String): PlatformApiKeyResponse {
        val value = rawValue.trim()
        if (value.isBlank()) throw UnprocessableEntityException("value nao pode ser vazio")
        // Validacao branda de formato (nao ser rigido demais): apenas tamanho plausivel.
        if (value.length < 8 || value.length > 500) {
            throw UnprocessableEntityException("value com tamanho implausivel para uma chave de API")
        }

        val before = describe(provider)
        val actor = SecurityUtils.currentPrincipal()?.userId
        val (enc, iv) = cipher.encrypt(value)

        val existing = repository.findFirstByProviderAndActiveTrue(provider)
        if (existing != null) {
            existing.valueEnc = enc
            existing.valueIv = iv
            existing.keyVersion += 1
            existing.updatedAt = Instant.now()
            existing.updatedBy = actor
            repository.save(existing)
        } else {
            repository.save(
                PlatformApiKey(
                    provider = provider,
                    valueEnc = enc,
                    valueIv = iv,
                    keyVersion = 1,
                    active = true,
                    updatedAt = Instant.now(),
                    updatedBy = actor,
                ),
            )
        }

        // A nova chave passa a valer imediatamente (cache do provider descartado).
        googleApiKeyProvider.invalidate()
        val after = describe(provider)

        auditService.record(
            action = "PLATFORM_API_KEY_UPSERT",
            targetEntity = provider.name,
            before = auditView(before),
            after = auditView(after),
        )
        return after
    }

    /**
     * Desativa a chave ativa de [provider] (volta ao fallback env). Mantem a linha como
     * historico (active=false) — o indice parcial so restringe as ativas. Invalida o
     * cache e audita mascarado. Idempotente: sem linha ativa, so retorna o estado atual.
     */
    @Transactional
    fun deactivate(provider: PlatformApiKeyProviderType): PlatformApiKeyResponse {
        val before = describe(provider)
        val existing = repository.findFirstByProviderAndActiveTrue(provider)
        if (existing != null) {
            existing.active = false
            existing.updatedAt = Instant.now()
            existing.updatedBy = SecurityUtils.currentPrincipal()?.userId
            repository.save(existing)
            googleApiKeyProvider.invalidate()
        }
        val after = describe(provider)
        auditService.record(
            action = "PLATFORM_API_KEY_DELETE",
            targetEntity = provider.name,
            before = auditView(before),
            after = auditView(after),
        )
        return after
    }

    /**
     * Testa a chave que o provider resolveria com UM geocode de amostra. NUNCA ecoa a
     * chave. Rate-limit por ator (1 a cada 5s) para nao torrar a cota Google.
     */
    fun test(provider: PlatformApiKeyProviderType): PlatformApiKeyTestResponse {
        rateLimitTest()
        val source = googleApiKeyProvider.source()
        if (source == GoogleKeySource.NONE) {
            return PlatformApiKeyTestResponse(
                ok = false,
                latencyMs = 0,
                source = source.name,
                message = "Nenhuma chave configurada (banco/env) para $provider",
            )
        }
        val start = System.currentTimeMillis()
        val resolved = runCatching {
            // Endereco fixo de amostra (Macapa) — a resolucao usa a MESMA chave do provider.
            geocodingService.geocode("Avenida FAB", "Central", "Macapa", "68900073")
        }
        val latency = System.currentTimeMillis() - start
        val ok = resolved.getOrNull() != null
        val message = when {
            ok -> "Geocode de amostra respondeu"
            resolved.isFailure -> "Falha ao chamar o Google (ver logs)"
            else -> "Google respondeu sem resultado (chave invalida ou sem cota?)"
        }
        return PlatformApiKeyTestResponse(ok = ok, latencyMs = latency, source = source.name, message = message)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Converte texto do path -> enum (allowlist). Provider desconhecido = 400. */
    fun parseProvider(raw: String): PlatformApiKeyProviderType =
        PlatformApiKeyProviderType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw BusinessException("Provedor de chave desconhecido: $raw")

    /** Mascara 4+4 chars (ex.: "AIza…gUms"). Curto demais -> "****". Vazio -> null. */
    private fun mask(plain: String): String? {
        if (plain.isBlank()) return null
        if (plain.length < 8) return "****"
        return plain.take(4) + "…" + plain.takeLast(4)
    }

    /** Visao MASCARADA para a auditoria — jamais o valor (so status/source/masked/versao). */
    private fun auditView(r: PlatformApiKeyResponse): Map<String, Any?> =
        mapOf(
            "status" to r.status,
            "source" to r.source,
            "masked" to r.masked,
            "keyVersion" to r.keyVersion,
        )

    private fun rateLimitTest() {
        val actor = SecurityUtils.currentPrincipal()?.userId ?: return
        val now = System.currentTimeMillis()
        val last = testLastCallByActor.put(actor, now)
        if (last != null && now - last < testMinIntervalMs) {
            // Repoe o carimbo anterior para nao "renovar" a janela a cada tentativa negada.
            testLastCallByActor[actor] = last
            throw TooManyRequestsException("Aguarde alguns segundos antes de testar a chave novamente")
        }
    }
}

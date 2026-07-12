package com.menuflow.dispatch

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.menuflow.crypto.SecretCipher
import com.menuflow.model.control.PlatformApiKeyProviderType
import com.menuflow.repository.control.PlatformApiKeyRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/** De onde veio a chave resolvida (para a UI/Fase 2 — nunca expoe o valor). */
enum class GoogleKeySource { DB, ENV, NONE }

/**
 * Resolve a chave da API do Google (distancia/geocode) em RUNTIME, com precedencia:
 *
 *   1. BANCO DE CONTROLE  — linha ativa em platform_api_key (provider=GOOGLE_MAPS),
 *      decifrada com o [SecretCipher] (AES-256-GCM);
 *   2. ENV                — google.routes.api-key (env GOOGLE_ROUTES_API_KEY), o
 *      comportamento ATUAL de producao (a chave ja esta no .env.prod da A1);
 *   3. VAZIO ("")         — sem chave; os consumidores caem no Haversine/null como hoje.
 *
 * FALLBACK BLINDADO: qualquer falha ao ler o banco OU ao decifrar NAO derruba o
 * caminho de delivery — loga o tipo do erro (NUNCA a chave nem o ciphertext) e cai no
 * proximo nivel (env -> vazio). Com o banco vazio, resolve() == a env atual: o
 * comportamento de producao fica IDENTICO ate a Fase 2 gravar uma chave.
 *
 * CACHE (Caffeine, TTL ~5 min): evita um SELECT no controle a cada geocode/distancia.
 * A Fase 2 chama [invalidate] no save/rotate/delete da chave; como a A1 e instancia
 * unica, o evict explicito basta (sem broadcast entre nos).
 */
@Service
class GoogleApiKeyProvider(
    private val repository: PlatformApiKeyRepository,
    private val cipher: SecretCipher,
    @Value("\${google.routes.api-key:}") private val envKey: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Chave resolvida + origem, memoizada em conjunto (a origem acompanha o valor). */
    private data class Resolved(val key: String, val source: GoogleKeySource)

    private val cache: Cache<PlatformApiKeyProviderType, Resolved> = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .maximumSize(8)
        .build()

    /** A chave a usar agora (""=sem chave). Consumida por requisicao pelos providers. */
    fun resolve(): String = resolved().key

    /** DB | ENV | NONE — para a UI/diagnostico da Fase 2 (sem devolver o valor). */
    fun source(): GoogleKeySource = resolved().source

    /** Descarta o cache; a Fase 2 chama isto (POS-COMMIT) ao gravar/rotacionar/apagar. */
    fun invalidate() = cache.invalidate(PlatformApiKeyProviderType.GOOGLE_MAPS)

    /**
     * Resolve a chave lendo o estado ATUAL (banco+env) SEM tocar no cache Caffeine. Usado
     * pela Fase 2 dentro de uma transacao de escrita para montar a resposta/auditoria a
     * partir do estado ainda-nao-commitado, sem POLUIR o cache compartilhado (que so deve
     * ser invalidado pos-commit). Devolve (origem, chave em claro).
     */
    fun resolveUncached(): Pair<GoogleKeySource, String> {
        val r = load()
        return r.source to r.key
    }

    private fun resolved(): Resolved =
        cache.get(PlatformApiKeyProviderType.GOOGLE_MAPS) { load() }

    private fun load(): Resolved {
        val dbKey = readFromDb()
        if (dbKey != null && dbKey.isNotBlank()) return Resolved(dbKey, GoogleKeySource.DB)
        if (envKey.isNotBlank()) return Resolved(envKey, GoogleKeySource.ENV)
        return Resolved("", GoogleKeySource.NONE)
    }

    /**
     * Le e decifra a chave ativa do banco de controle. Retorna null (para cair no
     * fallback) tanto quando NAO ha linha quanto quando algo falha — sem estourar e
     * sem vazar segredo no log.
     */
    private fun readFromDb(): String? {
        val row = try {
            repository.findFirstByProviderAndActiveTrue(PlatformApiKeyProviderType.GOOGLE_MAPS)
        } catch (e: Exception) {
            log.warn(
                "Falha ao consultar platform_api_key (GOOGLE_MAPS); usando fallback env/vazio. Causa: {}",
                e.javaClass.simpleName,
            )
            return null
        } ?: return null

        return try {
            cipher.decrypt(row.valueEnc, row.valueIv)
        } catch (e: Exception) {
            // NUNCA logar a chave nem o ciphertext — so a classe do erro e a versao de chave.
            log.error(
                "Falha ao decifrar platform_api_key GOOGLE_MAPS (key_version={}); caindo no fallback env/vazio. Causa: {}",
                row.keyVersion,
                e.javaClass.simpleName,
            )
            null
        }
    }
}

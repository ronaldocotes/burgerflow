package com.menuflow.ads

import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.exception.ServiceUnavailableException
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.AdAccount
import com.menuflow.model.AdAccountStatus
import com.menuflow.model.AdProvider
import com.menuflow.model.AdTokenType
import com.menuflow.repository.tenant.AdAccountRepository
import com.menuflow.service.AuditLogService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * Conexao read-only de conta de anuncio da Meta (Fase 8.0). Tudo no banco do TENANT
 * (db-per-tenant), isolado por restaurante.
 *
 * Fluxo de connect: (1) valida o token colado chamando a Meta FORA de qualquer
 * transacao de banco (nao seguramos uma tx do Postgres durante um HTTP de ~10s); se a
 * Meta aceitar, (2) cifra o token com IfoodTokenCipher e faz upsert das contas numa
 * transacao curta. Token invalido -> nada e salvo. O token NUNCA volta em resposta.
 *
 * Idempotencia: reconectar a MESMA conta (mesmo external_account_id) atualiza a linha
 * existente (novo token/metadados) em vez de duplicar — apoiado pela UNIQUE
 * (provider, external_account_id) da V58.
 */
@Service
class AdAccountService(
    private val repository: AdAccountRepository,
    private val metaGraphClient: MetaGraphClient,
    private val cipher: IfoodTokenCipher,
    private val auditService: AuditLogService,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // TransactionTemplate (nao @Transactional na propria connect) para: (a) manter o
    // HTTP fora da transacao e (b) evitar self-invocation (um @Transactional chamado
    // de outro metodo do MESMO bean nao passa pelo proxy e seria ignorado).
    private val txTemplate = TransactionTemplate(txManager)

    /**
     * Valida o token na Meta e conecta TODAS as contas de anuncio que ele controla.
     * Um System User Token costuma dar acesso a mais de uma conta; devolvemos todas as
     * recem-conectadas em vez de escolher uma arbitrariamente.
     */
    fun connect(rawToken: String, userId: UUID): List<AdAccountResponse> {
        val token = rawToken.trim()

        val metaAccounts = try {
            metaGraphClient.fetchAdAccounts(token)
        } catch (e: MetaTokenInvalidException) {
            // 400: erro do usuario (token ruim). Mensagem clara, nada persistido.
            throw BusinessException("Token da Meta invalido ou expirado. Gere um novo System User Token e cole novamente.")
        } catch (e: MetaGraphException) {
            // 503: dependencia externa. Distinto do token ruim para o cliente saber que e transitorio.
            throw ServiceUnavailableException("Nao foi possivel validar o token com a Meta agora. Tente novamente em instantes.")
        }

        if (metaAccounts.isEmpty()) {
            throw BusinessException("O token e valido, mas nao encontramos nenhuma conta de anuncio associada a ele.")
        }

        val saved = txTemplate.execute {
            metaAccounts.map { meta -> upsert(meta, token, userId) }
        } ?: emptyList()

        return saved.map { AdAccountResponse.from(it) }
    }

    /** Cria ou atualiza (reconnect) a conta; cifra o token com IV proprio por linha. */
    private fun upsert(meta: MetaAdAccountDto, rawToken: String, userId: UUID): AdAccount {
        val (enc, iv) = cipher.encrypt(rawToken)
        val existing = repository.findByProviderAndExternalAccountId(AdProvider.META, meta.externalAccountId)
        val entity = if (existing != null) {
            existing.apply {
                accountName = meta.name
                currency = meta.currency
                timezoneName = meta.timezoneName
                tokenEnc = enc
                tokenIv = iv
                tokenType = AdTokenType.SYSTEM_USER
                status = AdAccountStatus.CONNECTED
                lastError = null
                connectedByUserId = userId
            }
        } else {
            AdAccount(
                provider = AdProvider.META,
                externalAccountId = meta.externalAccountId,
                accountName = meta.name,
                currency = meta.currency,
                timezoneName = meta.timezoneName,
                tokenEnc = enc,
                tokenIv = iv,
                tokenType = AdTokenType.SYSTEM_USER,
                status = AdAccountStatus.CONNECTED,
                connectedByUserId = userId,
            )
        }
        val persisted = repository.save(entity)
        // Auditoria no banco do tenant (mesma tx, REQUIRED). NUNCA loga o token.
        auditService.log(
            action = if (existing != null) "AD_ACCOUNT_RECONNECT" else "AD_ACCOUNT_CONNECT",
            entity = "ad_account",
            entityId = persisted.id,
            after = mapOf(
                "provider" to persisted.provider.name,
                "externalAccountIdLast4" to persisted.externalAccountId.takeLast(4),
                "accountName" to persisted.accountName,
            ),
            actorUserId = userId,
        )
        log.info("Conta de anuncio {} conectada (last4={})", persisted.provider, persisted.externalAccountId.takeLast(4))
        return persisted
    }

    /** Lista as contas conectadas do tenant. NUNCA devolve o token. */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(): List<AdAccountResponse> =
        repository.findAllByOrderByCreatedAtAsc().map { AdAccountResponse.from(it) }

    /**
     * Desconecta a conta: REMOVE a linha (hard delete). Decisao: apagar elimina o token
     * cifrado do banco em vez de deixar um segredo orfao sem uso — melhor postura de
     * seguranca. O historico fica preservado no audit_log (registrado antes do delete).
     */
    @Transactional("tenantTransactionManager")
    fun disconnect(id: UUID) {
        val account = repository.findById(id)
            .orElseThrow { ResourceNotFoundException("Conta de anuncio nao encontrada: $id") }
        auditService.log(
            action = "AD_ACCOUNT_DISCONNECT",
            entity = "ad_account",
            entityId = account.id,
            before = mapOf(
                "provider" to account.provider.name,
                "externalAccountIdLast4" to account.externalAccountId.takeLast(4),
                "accountName" to account.accountName,
            ),
        )
        repository.delete(account)
    }
}

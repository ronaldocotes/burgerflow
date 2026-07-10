package com.menuflow.ads

import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.AdAccount
import com.menuflow.model.AdAccountStatus
import com.menuflow.model.AdMetricsSnapshot
import com.menuflow.repository.tenant.AdAccountRepository
import com.menuflow.repository.tenant.AdMetricsSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Coleta e leitura das metricas de anuncio (Fase 8.1), a nivel de CONTA. Tudo no banco do
 * TENANT (db-per-tenant), isolado por restaurante.
 *
 * Coleta ([snapshotAllAccounts]): chamada pelo AdMetricsSnapshotJob JA dentro do
 * TenantContext do tenant. Para cada conta CONNECTED, decifra o token, chama a Meta FORA
 * de transacao (HTTP de ~10s nao segura tx do Postgres) e faz upsert idempotente por
 * (conta, dia) numa tx curta. Resiliente por conta: token expirado marca a conta EXPIRED
 * e segue; rate-limit apenas pula a conta neste tick; outro erro e logado e pulado — uma
 * conta ruim nunca derruba as demais. O token NUNCA vai a log/DTO/erro.
 *
 * Leitura ([list]): ultimos N dias de UMA conta para o grafico. db-per-tenant ja isola por
 * banco; alem disso exigimos que a conta exista no banco corrente (404 caso contrario) —
 * conta de outro tenant simplesmente nao existe aqui.
 */
@Service
class AdMetricsService(
    private val adAccountRepository: AdAccountRepository,
    private val snapshotRepository: AdMetricsSnapshotRepository,
    private val metaGraphClient: MetaGraphClient,
    private val cipher: IfoodTokenCipher,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // TransactionTemplate (nao @Transactional na propria snapshotAllAccounts) para manter
    // o HTTP fora da transacao e evitar self-invocation (mesmo padrao do AdAccountService).
    private val txTemplate = TransactionTemplate(txManager)

    /**
     * Coleta metricas de todas as contas CONNECTED do tenant corrente. Intervalo [ontem,
     * hoje]: ontem ja consolidado + hoje parcial (is_partial=true). Idempotente: re-rodar
     * o dia atualiza a linha em vez de duplicar.
     */
    fun snapshotAllAccounts() {
        val accounts = adAccountRepository.findAllByStatus(AdAccountStatus.CONNECTED)
        accounts.forEach { account ->
            val zone = accountZone(account)
            val today = LocalDate.now(zone)
            val since = today.minusDays(1)
            try {
                // Decifra o token apenas em memoria, para a chamada; nunca logado.
                val token = cipher.decrypt(account.tokenEnc, account.tokenIv)
                val insights = metaGraphClient.fetchAccountInsights(
                    accessToken = token,
                    externalAccountId = account.externalAccountId,
                    since = since,
                    until = today,
                )
                txTemplate.execute {
                    insights.forEach { row -> upsertSnapshot(account.id!!, row, isPartial = row.date == today) }
                }
            } catch (e: MetaTokenInvalidException) {
                // Token expirado/revogado: marca a conta e NAO derruba as demais.
                markExpired(account.id!!, e.message)
            } catch (e: MetaRateLimitException) {
                log.warn("[ads-metrics] rate-limit na conta {} (last4={}); pulando neste tick",
                    account.provider, account.externalAccountId.takeLast(4))
            } catch (e: Exception) {
                // Rede/5xx/erro Graph/decifra: pula a conta, mantem a varredura viva.
                log.error("[ads-metrics] falha ao coletar metricas da conta {} (last4={}): {}",
                    account.provider, account.externalAccountId.takeLast(4), e.message)
            }
        }
    }

    /** Upsert idempotente por (conta, dia): atualiza a linha existente ou cria uma nova. */
    private fun upsertSnapshot(adAccountId: UUID, row: MetaInsightDto, isPartial: Boolean) {
        val entity = snapshotRepository.findByAdAccountIdAndSnapshotDate(adAccountId, row.date)
            ?: AdMetricsSnapshot(adAccountId = adAccountId, snapshotDate = row.date)
        entity.spendCents = row.spendCents
        entity.impressions = row.impressions
        entity.reach = row.reach
        entity.clicks = row.clicks
        entity.ctrMilli = row.ctrMilli
        entity.cpcCents = row.cpcCents
        entity.isPartial = isPartial
        entity.fetchedAt = Instant.now()
        snapshotRepository.save(entity)
    }

    /** Marca a conta como EXPIRED numa tx propria; releitura por id evita entidade stale. */
    private fun markExpired(adAccountId: UUID, reason: String?) {
        txTemplate.execute {
            adAccountRepository.findById(adAccountId).ifPresent { acc ->
                acc.status = AdAccountStatus.EXPIRED
                // Mensagem da Meta (sem token); ajuda o restaurante a entender que precisa reconectar.
                acc.lastError = "Token recusado pela Meta ao coletar metricas: ${reason ?: "sem detalhe"}"
                adAccountRepository.save(acc)
            }
        }
        log.warn("[ads-metrics] conta {} marcada EXPIRED (token recusado pela Meta)", adAccountId)
    }

    /**
     * Ultimos [days] dias de metricas de UMA conta, em ordem de data (para o grafico).
     * Escopo do tenant: a conta precisa existir no banco corrente (404 caso contrario).
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(adAccountId: UUID, days: Int): List<AdMetricsResponse> {
        adAccountRepository.findById(adAccountId)
            .orElseThrow { ResourceNotFoundException("Conta de anuncio nao encontrada: $adAccountId") }
        val safeDays = days.coerceIn(1, 365)
        val from = LocalDate.now().minusDays((safeDays - 1).toLong())
        return snapshotRepository
            .findByAdAccountIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(adAccountId, from)
            .map { AdMetricsResponse.from(it) }
    }

    /** Zona da conta (para decidir "hoje"); cai no default do servidor se ausente/invalida. */
    private fun accountZone(account: AdAccount): ZoneId =
        account.timezoneName?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
}

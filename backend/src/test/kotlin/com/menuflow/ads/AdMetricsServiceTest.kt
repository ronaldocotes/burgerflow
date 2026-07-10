package com.menuflow.ads

import com.menuflow.IntegrationTestBase
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.AdAccount
import com.menuflow.model.AdAccountStatus
import com.menuflow.model.control.SubscriptionPlan
import com.menuflow.model.control.Tenant
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.tenant.AdAccountRepository
import com.menuflow.repository.tenant.AdMetricsSnapshotRepository
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Fase 8.1 (Dashboard de Insights) — nivel de service, com o MetaGraphClient MOCKADO
 * (nenhuma chamada real a Graph API). Roda dentro do TenantContext (o job normalmente o
 * vincula). Prova: (1) snapshot cria 1 linha/dia e e idempotente por (conta,dia);
 * (3) o dia corrente e marcado is_partial=true; (4) token expirado numa conta a marca
 * EXPIRED e NAO derruba a coleta das demais.
 */
class AdMetricsServiceTest @Autowired constructor(
    private val tenantRepository: TenantRepository,
    private val adAccountRepository: AdAccountRepository,
    private val snapshotRepository: AdMetricsSnapshotRepository,
    private val cipher: IfoodTokenCipher,
    private val service: AdMetricsService,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var metaGraphClient: MetaGraphClient

    @AfterEach
    fun clear() = TenantContext.clear()

    private val rawToken = "SYSUSR-EAAB-valid-token-abcdefghijklmnop-1234"
    private val zone = ZoneId.of("America/Sao_Paulo")

    private fun seedTenant(): String {
        val slug = "adsm_${UUID.randomUUID().toString().take(8)}"
        tenantRepository.save(Tenant(slug = slug, displayName = "Ads Metrics", subscriptionPlan = SubscriptionPlan.ENTERPRISE))
        return slug
    }

    /** Cria uma conta CONNECTED com o token cifrado (tz Sao Paulo) e devolve seu id. */
    private fun seedAccount(externalId: String): UUID {
        val (enc, iv) = cipher.encrypt(rawToken)
        val acc = adAccountRepository.save(
            AdAccount(
                externalAccountId = externalId,
                accountName = "Conta $externalId",
                currency = "BRL",
                timezoneName = zone.id,
                tokenEnc = enc,
                tokenIv = iv,
            ),
        )
        return acc.id!!
    }

    @Test
    fun `snapshot cria uma linha por dia, marca o dia corrente como parcial e e idempotente`() {
        val slug = seedTenant()
        TenantContext.set(slug)
        val accountId = seedAccount("1234567890")

        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        Mockito.`when`(metaGraphClient.fetchAccountInsights(rawToken, "1234567890", yesterday, today))
            .thenReturn(
                listOf(
                    MetaInsightDto(yesterday, spendCents = 5000, impressions = 2000, reach = 1500, clicks = 100, ctrMilli = 5000, cpcCents = 50),
                    MetaInsightDto(today, spendCents = 1234, impressions = 500, reach = 400, clicks = 20, ctrMilli = 4000, cpcCents = 61),
                ),
            )

        service.snapshotAllAccounts()

        val rows = snapshotRepository.findByAdAccountIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(accountId, yesterday)
        assertEquals(2, rows.size, "uma linha por dia")
        assertEquals(yesterday, rows[0].snapshotDate)
        assertFalse(rows[0].isPartial, "ontem ja consolidado")
        assertEquals(5000L, rows[0].spendCents)
        assertEquals(today, rows[1].snapshotDate)
        assertTrue(rows[1].isPartial, "o dia corrente e parcial")
        assertEquals(1234L, rows[1].spendCents)

        // Idempotencia: re-rodar com valores atualizados do dia NAO duplica, atualiza a linha.
        Mockito.`when`(metaGraphClient.fetchAccountInsights(rawToken, "1234567890", yesterday, today))
            .thenReturn(
                listOf(
                    MetaInsightDto(yesterday, spendCents = 5000, impressions = 2000, reach = 1500, clicks = 100, ctrMilli = 5000, cpcCents = 50),
                    MetaInsightDto(today, spendCents = 9999, impressions = 800, reach = 600, clicks = 30, ctrMilli = 3750, cpcCents = 333),
                ),
            )
        service.snapshotAllAccounts()

        val after = snapshotRepository.findByAdAccountIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(accountId, yesterday)
        assertEquals(2, after.size, "re-rodar o dia atualiza, nao duplica (UNIQUE conta+dia)")
        assertEquals(9999L, after[1].spendCents, "o valor do dia corrente foi atualizado")
    }

    @Test
    fun `token expirado numa conta a marca EXPIRED e nao impede a coleta das demais`() {
        val slug = seedTenant()
        TenantContext.set(slug)
        val badId = seedAccount("1111111111")
        val goodId = seedAccount("2222222222")

        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        // A conta ruim: a Meta recusa o token (190).
        Mockito.`when`(metaGraphClient.fetchAccountInsights(rawToken, "1111111111", yesterday, today))
            .thenThrow(MetaTokenInvalidException("Invalid OAuth access token"))
        // A conta boa: coleta normal.
        Mockito.`when`(metaGraphClient.fetchAccountInsights(rawToken, "2222222222", yesterday, today))
            .thenReturn(listOf(MetaInsightDto(today, spendCents = 700, impressions = 100, reach = 90, clicks = 10, ctrMilli = 10000, cpcCents = 70)))

        service.snapshotAllAccounts()

        // A ruim virou EXPIRED e nao gerou snapshot...
        val bad = adAccountRepository.findById(badId).get()
        assertEquals(AdAccountStatus.EXPIRED, bad.status)
        assertTrue(snapshotRepository.findByAdAccountIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(badId, yesterday).isEmpty())
        // ...e a boa foi coletada normalmente (a coleta nao foi derrubada).
        val good = adAccountRepository.findById(goodId).get()
        assertEquals(AdAccountStatus.CONNECTED, good.status)
        val goodRows = snapshotRepository.findByAdAccountIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(goodId, yesterday)
        assertEquals(1, goodRows.size)
        assertEquals(700L, goodRows.first().spendCents)
    }
}

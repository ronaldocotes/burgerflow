package com.menuflow.service

import com.menuflow.model.CampaignStatus
import com.menuflow.model.SendStatus
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.CampaignRepository
import com.menuflow.repository.tenant.CampaignSendRepository
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Despacho ASSINCRONO das campanhas de WhatsApp (Fase 3.4), com mitigacoes anti-ban
 * obrigatorias (WAHA usa API nao-oficial):
 *  - delay aleatorio entre mensagens (tenant_config, default 15-45s);
 *  - pausa longa (10-15min) a cada lote de [BATCH_SIZE] envios;
 *  - spintax (variacao por destinatario) ja vem aplicada na mensagem pre-calculada;
 *  - filtro de opt-in (so destinatarios com opt-in entram na campanha) + recheque por
 *    envio (cliente que revogou no meio vira OPT_OUT, nao recebe);
 *  - fallback automatico para o numero reserva (sessao WAHA) se o primario falhar.
 *
 * NUNCA roda no thread HTTP (@Async) e NUNCA segura uma transacao durante os delays:
 * cada envio le/atualiza sua linha em transacoes curtas (TransactionTemplate). Em
 * db-per-tenant, o thread async perde o TenantContext, entao [dispatchAsync] vincula
 * o slug recebido antes de processar (mesmo padrao do PixReconciliationJob/Loyalty).
 */
@Component
class CampaignDispatcher(
    private val campaignRepository: CampaignRepository,
    private val campaignSendRepository: CampaignSendRepository,
    private val customerRepository: CustomerRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val whatsAppService: WhatsAppService,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    /** Config de despacho consolidada para o loop (lida 1x no inicio). */
    private data class DispatchConfig(
        val primaryPhone: String?,
        val fallbackPhone: String?,
        val delayMinSeconds: Int,
        val delayMaxSeconds: Int,
    )

    /**
     * Ponto de entrada assincrono: vincula o tenant do slug e roda o despacho.
     * Fail-safe: qualquer falha marca a campanha como FAILED e e logada.
     */
    @Async
    fun dispatchAsync(tenantSlug: String, campaignId: UUID) {
        val previous = TenantContext.get()
        TenantContext.set(tenantSlug)
        try {
            runDispatch(campaignId)
        } catch (e: Exception) {
            log.error("Falha no despacho da campanha {}: {}", campaignId, e.message)
            runCatching {
                txTemplate.execute {
                    campaignRepository.findById(campaignId).orElse(null)?.let {
                        it.status = CampaignStatus.FAILED
                        campaignRepository.save(it)
                    }
                }
            }
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /**
     * Loop de despacho (sincrono e testavel; assume o TenantContext ja vinculado).
     * Marca RUNNING, processa os QUEUED um a um com delay e finaliza
     * (COMPLETED se esvaziou a fila; respeita PAUSED se o operador pausou no meio).
     */
    fun runDispatch(campaignId: UUID) {
        val config = txTemplate.execute {
            val campaign = campaignRepository.findById(campaignId).orElse(null)
                ?: return@execute null
            campaign.status = CampaignStatus.RUNNING
            campaign.startedAt = campaign.startedAt ?: Instant.now()
            campaignRepository.save(campaign)
            currentConfig().let {
                DispatchConfig(it.wahaPrimaryPhone, it.wahaFallbackPhone, it.campaignDelayMinSeconds, it.campaignDelayMaxSeconds)
            }
        } ?: return

        val sendIds = txTemplate.execute {
            campaignSendRepository.findIdsByCampaignIdAndStatus(campaignId, SendStatus.QUEUED)
        } ?: emptyList()

        if (sendIds.isEmpty()) {
            finalize(campaignId)
            return
        }

        var processed = 0
        for (sendId in sendIds) {
            val status = txTemplate.execute { campaignRepository.findById(campaignId).orElse(null)?.status }
            if (status == CampaignStatus.PAUSED) {
                log.info("Campanha {} pausada; interrompendo despacho", campaignId)
                return // mantem PAUSED + os QUEUED restantes para retomada
            }

            val sent = processSend(campaignId, sendId, config)
            if (sent != null) {
                processed++
                if (processed % BATCH_SIZE == 0) sleepBatchPause() else sleepBetween(config)
            }
        }
        finalize(campaignId)
    }

    /**
     * Processa UM envio sem segurar transacao durante a chamada HTTP:
     *  1. le a linha + opt-in do cliente (tx curta);
     *  2. se ja nao esta QUEUED -> pula; se cliente revogou opt-in -> OPT_OUT;
     *  3. envia pelo WAHA (fora de transacao) com fallback de sessao;
     *  4. grava SENT/FAILED + incrementa os contadores (tx curta).
     * Retorna true/false (enviou) ou null quando o envio nao foi tentado (pulado).
     */
    private fun processSend(campaignId: UUID, sendId: UUID, config: DispatchConfig): Boolean? {
        val data = txTemplate.execute {
            val s = campaignSendRepository.findById(sendId).orElse(null) ?: return@execute null
            if (s.status != SendStatus.QUEUED) return@execute null
            val optIn = customerRepository.findById(s.customerId).orElse(null)?.marketingOptIn ?: false
            SendData(s.phone, s.message, optIn)
        } ?: return null

        if (!data.optIn) {
            txTemplate.execute {
                campaignSendRepository.findById(sendId).orElse(null)?.let {
                    it.status = SendStatus.OPT_OUT
                    campaignSendRepository.save(it)
                }
            }
            return null // nao conta como envio nem aplica delay
        }

        // Envio fora de transacao (chamada HTTP externa nao prende conexao do banco).
        var ok = whatsAppService.sendCampaign(data.phone, data.message, config.primaryPhone)
        if (!ok && !config.fallbackPhone.isNullOrBlank()) {
            ok = whatsAppService.sendCampaign(data.phone, data.message, config.fallbackPhone)
        }
        val sentOk = ok

        txTemplate.execute {
            campaignSendRepository.findById(sendId).orElse(null)?.let { s ->
                s.status = if (sentOk) SendStatus.SENT else SendStatus.FAILED
                s.sentAt = Instant.now()
                s.errorMessage = if (sentOk) null else "Falha no envio WAHA"
                campaignSendRepository.save(s)
            }
            campaignRepository.findById(campaignId).orElse(null)?.let { c ->
                if (sentOk) c.sentCount += 1 else c.failedCount += 1
                campaignRepository.save(c)
            }
        }
        return sentOk
    }

    /** Finaliza: COMPLETED se nao ha mais QUEUED; respeita PAUSED. */
    private fun finalize(campaignId: UUID) {
        txTemplate.execute {
            val c = campaignRepository.findById(campaignId).orElse(null) ?: return@execute
            if (c.status == CampaignStatus.PAUSED) return@execute
            c.status = CampaignStatus.COMPLETED
            c.completedAt = Instant.now()
            campaignRepository.save(c)
        }
    }

    /** Config do tenant ou default (tenant sem linha de config). */
    private fun currentConfig(): TenantConfig =
        tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: TenantConfig()

    /** Delay aleatorio entre mensagens. min==max==0 -> sem espera (usado em teste). */
    private fun sleepBetween(config: DispatchConfig) {
        val lo = config.delayMinSeconds.coerceAtLeast(0)
        val hi = config.delayMaxSeconds.coerceAtLeast(lo)
        if (hi <= 0) return
        val secs = if (hi == lo) lo.toLong() else Random.nextLong(lo.toLong(), hi.toLong() + 1)
        if (secs > 0) Thread.sleep(secs * 1000)
    }

    /** Pausa longa de 10-15min a cada lote (nunca fixa). */
    private fun sleepBatchPause() {
        Thread.sleep(Random.nextLong(600, 901) * 1000)
    }

    private data class SendData(val phone: String, val message: String, val optIn: Boolean)

    companion object {
        /** Tamanho do lote antes da pausa longa. */
        const val BATCH_SIZE = 50
    }
}

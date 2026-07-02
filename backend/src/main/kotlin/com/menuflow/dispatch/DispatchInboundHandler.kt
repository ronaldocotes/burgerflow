package com.menuflow.dispatch

import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.security.ratelimit.WebhookMessageDeduplicator
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Roteia as mensagens do GRUPO de motoboys (Fase B2) para o aceite atomico. Chamado pelo
 * webhook do WAHA quando chega uma mensagem de grupo (@g.us). Roda @Async para o webhook
 * devolver 200 imediatamente (o WAHA reentrega se demorar).
 *
 * db-per-tenant: como @Async perde o TenantContext, revinculamos o slug do evento antes de
 * tocar o banco do tenant. Fluxo:
 *  1. carrega a config do tenant; ignora se dispatch desligado;
 *  2. so processa mensagens do grupo de motoboys configurado (motoboy_group_jid) — descarta
 *     qualquer outro grupo silenciosamente;
 *  3. so mensagens no formato "ACEITO <codigo>" interessam (regex); demais sao ignoradas
 *     ANTES da dedup (conversa comum no grupo nao consome chave de idempotencia);
 *  4. dedup por messageId (o WAHA reentrega);
 *  5. delega ao DispatchService.acceptOffer (que resolve o motoboy pelo JID — find-or-create
 *     provisional — e faz o CAS atomico); responde ao participante nos casos de falha.
 *
 * Anti-IDOR / privacidade: o handler nunca revela endereco/telefone; o DispatchService cria
 * o motoboy pelo telefone extraido do JID verificado do participante (nao de dado do corpo),
 * e o endereco so vai em DM ao vencedor (DispatchEventListener). Fail-open: qualquer falha e
 * logada e engolida (o webhook nunca retorna erro ao WAHA).
 */
@Component
class DispatchInboundHandler(
    private val dispatchService: DispatchService,
    private val tenantConfigRepository: TenantConfigRepository,
    private val dispatchWhatsAppService: DispatchWhatsAppService,
    private val deduplicator: WebhookMessageDeduplicator,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    companion object {
        /** "ACEITO AB12CD34" (case-insensitive, com espacos folgados). Captura o codigo de aceite. */
        val ACCEPT_PATTERN = Regex("""(?i)^\s*ACEITO\s+([A-Z0-9]{2,8})\s*$""")
    }

    /** Ponto de entrada ASSINCRONO chamado pelo webhook (fora do thread HTTP). */
    @Async
    fun handleAsync(tenantSlug: String, groupJid: String, participantJid: String, body: String, messageId: String) {
        val previous = TenantContext.get()
        TenantContext.set(tenantSlug)
        try {
            handle(tenantSlug, groupJid, participantJid, body, messageId)
        } catch (e: Exception) {
            log.error("Falha ao processar mensagem do grupo (tenant {}): {}", tenantSlug, e.message)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /** Processamento sincrono e testavel (assume TenantContext ja vinculado). */
    fun handle(tenantSlug: String, groupJid: String, participantJid: String, body: String, messageId: String) {
        // 1-2. Config + guarda de grupo: so o grupo de motoboys configurado interessa.
        val config = txTemplate.execute { tenantConfigRepository.findFirstByOrderByCreatedAtAsc() } ?: return
        if (!config.dispatchEnabled) return
        if (config.motoboyGroupJid.isNullOrBlank() || groupJid != config.motoboyGroupJid) return

        // 3. So "ACEITO <codigo>" segue adiante (conversa comum e ignorada antes da dedup).
        val match = ACCEPT_PATTERN.find(body.trim()) ?: return
        val code = match.groupValues[1].uppercase()

        // 4. Idempotencia por messageId (o WAHA reentrega).
        if (messageId.isNotBlank() && !deduplicator.markIfNew("dispatch:$tenantSlug:$messageId")) {
            log.debug("Mensagem de grupo {} ja processada (tenant {}); ignorando", messageId, tenantSlug)
            return
        }

        // 5. Aceite atomico: DispatchService resolve o motoboy pelo JID e faz o CAS.
        when (dispatchService.acceptOffer(code, participantJid, messageId)) {
            DispatchService.AcceptOutcome.ALREADY_TAKEN ->
                dispatchWhatsAppService.sendAlreadyTaken(participantJid, code, config)
            DispatchService.AcceptOutcome.NO_OFFER, DispatchService.AcceptOutcome.NO_ORDER ->
                dispatchWhatsAppService.sendCodeNotFound(participantJid, code, config)
            // ACCEPTED: o ACK no grupo sai pelo evento RideAssigned. DUPLICATE: silencio.
            DispatchService.AcceptOutcome.ACCEPTED, DispatchService.AcceptOutcome.DUPLICATE -> Unit
        }
    }
}

package com.menuflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.dto.ConversionDispatchResponse
import com.menuflow.event.OrderPaidEvent
import com.menuflow.model.ConversionDispatch
import com.menuflow.model.ConversionPlatform
import com.menuflow.model.ConversionStatus
import com.menuflow.model.TenantConfig
import com.menuflow.repository.tenant.ConversionDispatchRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Rastreamento de conversao (Fase 3.7): replica cada pedido PAGO para a Meta CAPI
 * (Conversions API) e para o Google via sGTM. O objetivo e devolver as conversoes
 * offline (vendas pelo PDV/cardapio) para as plataformas otimizarem os anuncios.
 *
 * Fluxo:
 *  1. [scheduleConversions] reage ao OrderPaidEvent (AFTER_COMMIT) e cria os
 *     despachos PENDING (um por plataforma configurada) — SEM tocar a rede;
 *  2. [processDispatches] (chamado pelo ConversionDispatchJob a cada intervalo, e
 *     pelo retry manual) envia os PENDING e retenta os FAILED com backoff;
 *  3. apos [MAX_ATTEMPTS] falhas o despacho vira SKIPPED.
 *
 * FAIL-OPEN total: nada aqui pode derrubar o pedido. Erros de rede viram FAILED e
 * sao retentados depois. PII (telefone) e hasheada (SHA-256) ANTES de sair do
 * processo; o telefone em claro nunca e logado nem persistido aqui.
 *
 * Timeout de 5s nas chamadas externas (Meta/Google) — alvo lento nunca prende o job.
 */
@Service
class ConversionService(
    private val conversionDispatchRepository: ConversionDispatchRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val orderRepository: OrderRepository,
    private val objectMapper: ObjectMapper,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    // Cliente HTTP com timeout curto (connect/read 5s). Sem baseUrl: cada plataforma
    // tem URL propria (Meta fixa; Google e o sGTM configurado pelo dono).
    private val http: RestClient = builder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(HTTP_TIMEOUT_MS)
                setReadTimeout(HTTP_TIMEOUT_MS)
            },
        )
        .build()

    // ---------------------------------------------------------------------------
    // 1. Agendamento (reage ao pagamento) — cria os despachos PENDING.
    // ---------------------------------------------------------------------------

    /** Listener AFTER_COMMIT do pagamento. Delega para [scheduleConversions] (testavel). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderPaid(event: OrderPaidEvent) {
        scheduleConversions(event)
    }

    /**
     * Cria os despachos PENDING do pedido pago (um por plataforma configurada).
     * Roteia para o banco do tenant pelo slug do evento (o thread pode ter perdido
     * o TenantContext pos-commit). Idempotente (indice unico + pre-checagem) e
     * fail-open. NAO faz HTTP — o envio fica a cargo do job/retry.
     */
    fun scheduleConversions(event: OrderPaidEvent) {
        val previous = TenantContext.get()
        TenantContext.set(event.tenantSlug)
        try {
            txTemplate.execute {
                val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc() ?: return@execute
                if (!config.conversionTrackingEnabled) {
                    log.debug("Rastreamento de conversao desligado para o tenant {}", event.tenantSlug)
                    return@execute
                }
                if (!config.metaPixelId.isNullOrBlank()) {
                    createDispatch(event.orderId, ConversionPlatform.META)
                }
                if (!config.googleSgtmUrl.isNullOrBlank()) {
                    createDispatch(event.orderId, ConversionPlatform.GOOGLE)
                }
            }
        } catch (e: Exception) {
            log.error("Falha ao agendar conversoes do pedido {}: {}", event.orderId, e.message)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /** Cria um despacho PENDING se ainda nao existir (idempotencia alem do indice unico). */
    private fun createDispatch(orderId: UUID, platform: ConversionPlatform) {
        if (conversionDispatchRepository.existsByOrderIdAndPlatform(orderId, platform)) return
        conversionDispatchRepository.save(
            ConversionDispatch(
                orderId = orderId,
                platform = platform,
                status = ConversionStatus.PENDING,
                eventId = "order-$orderId",
            ),
        )
    }

    // ---------------------------------------------------------------------------
    // 2. Processamento (envio + retry) — chamado pelo job e pelo retry manual.
    // ---------------------------------------------------------------------------

    /**
     * Processa os despachos do tenant: envia os PENDING e retenta os FAILED elegiveis
     * (attempts < [MAX_ATTEMPTS] e backoff cumprido). Despachos FAILED que ja atingiram
     * o limite viram SKIPPED sem nova tentativa. Retorna quantos foram enviados com
     * sucesso neste tick.
     */
    fun processDispatches(tenantSlug: String): Int {
        val previous = TenantContext.get()
        TenantContext.set(tenantSlug)
        try {
            val config = txTemplate.execute { tenantConfigRepository.findFirstByOrderByCreatedAtAsc() } ?: return 0
            if (!config.conversionTrackingEnabled) return 0

            val candidates = txTemplate.execute {
                conversionDispatchRepository.findByStatusIn(
                    listOf(ConversionStatus.PENDING, ConversionStatus.FAILED),
                )
            } ?: emptyList()

            var sent = 0
            for (dispatch in candidates) {
                if (attemptDispatch(dispatch.id!!, config)) sent++
            }
            return sent
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /** Retry manual de um despacho (endpoint admin). Roteia para o tenant atual. */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun retryOne(id: UUID): Boolean {
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
            ?: throw com.menuflow.exception.ResourceNotFoundException("Configuracao do tenant inexistente")
        conversionDispatchRepository.findById(id).orElseThrow {
            com.menuflow.exception.ResourceNotFoundException("Despacho de conversao $id nao encontrado")
        }
        // O envio acontece fora desta tx (HTTP nao prende conexao do banco).
        return attemptDispatch(id, config)
    }

    /**
     * Tenta enviar UM despacho. Decide o que fazer pelo estado atual:
     *  - SENT/SKIPPED -> ignora (terminal);
     *  - attempts >= [MAX_ATTEMPTS] -> SKIPPED (esgotou; nao tenta);
     *  - FAILED sem backoff cumprido -> adia (nao tenta agora);
     *  - caso contrario -> monta o payload, faz o POST (fora de transacao) e grava o
     *    resultado: 2xx -> SENT; senao -> FAILED (ou SKIPPED se atingiu o limite).
     * Fail-open: qualquer excecao vira FAILED. Retorna true so em envio bem-sucedido.
     */
    private fun attemptDispatch(id: UUID, config: TenantConfig): Boolean {
        val dispatch = txTemplate.execute { conversionDispatchRepository.findById(id).orElse(null) } ?: return false
        if (dispatch.status == ConversionStatus.SENT || dispatch.status == ConversionStatus.SKIPPED) return false

        val now = Instant.now()
        if (dispatch.attempts >= MAX_ATTEMPTS) {
            txTemplate.execute { markSkipped(id) }
            return false
        }
        if (dispatch.status == ConversionStatus.FAILED && !backoffElapsed(dispatch, now)) {
            return false
        }

        val order = txTemplate.execute { orderRepository.findById(dispatch.orderId).orElse(null) }
        if (order == null) {
            txTemplate.execute { recordResult(id, success = false, code = null, body = "pedido inexistente", payloadHash = null) }
            return false
        }

        // Telefone hasheado ANTES de qualquer uso; nunca em claro daqui pra frente.
        val hashedPhone = order.customerPhone?.takeIf { it.isNotBlank() }?.let { listOf(hashPhone(it)) } ?: emptyList()
        val valueReais = order.totalCents / 100.0
        val eventId = dispatch.eventId ?: "order-${dispatch.orderId}"

        return try {
            val (code, body, payloadHash) = when (dispatch.platform) {
                ConversionPlatform.META -> sendMeta(config, order.id!!, eventId, order.createdAt, valueReais, hashedPhone)
                ConversionPlatform.GOOGLE -> sendGoogle(config, order.id!!, valueReais)
            }
            val ok = code in 200..299
            txTemplate.execute { recordResult(id, ok, code, body, payloadHash) }
            ok
        } catch (e: Exception) {
            // Fail-open: alvo lento/fora do ar -> FAILED, retentado depois.
            log.warn("Falha ao despachar conversao {} ({}): {}", id, dispatch.platform, e.message)
            txTemplate.execute { recordResult(id, success = false, code = null, body = e.message?.take(500), payloadHash = null) }
            false
        }
    }

    // ---------------------------------------------------------------------------
    // 3. Envio para cada plataforma (HTTP). Retorna (codigo, corpo, hashDoPayload).
    // ---------------------------------------------------------------------------

    private data class HttpOutcome(val code: Int, val body: String?, val payloadHash: String?)

    /**
     * Meta CAPI: POST graph.facebook.com/v18.0/{pixelId}/events?access_token=...
     * test_event_code (se configurado) direciona o evento para o teste do Events
     * Manager (nao conta como conversao real). PII (ph) ja vem hasheada.
     */
    private fun sendMeta(
        config: TenantConfig,
        orderId: UUID,
        eventId: String,
        eventTime: Instant,
        valueReais: Double,
        hashedPhone: List<String>,
    ): HttpOutcome {
        val data = mapOf(
            "event_name" to "Purchase",
            "event_time" to eventTime.epochSecond,
            "event_id" to eventId,
            "user_data" to mapOf(
                "ph" to hashedPhone,
                "em" to emptyList<String>(),
            ),
            "custom_data" to mapOf(
                "value" to valueReais,
                "currency" to "BRL",
                "content_ids" to listOf(orderId.toString()),
                "content_type" to "product",
            ),
            "action_source" to "website",
        )
        val payload = buildMap<String, Any> {
            put("data", listOf(data))
            config.metaTestEventCode?.takeIf { it.isNotBlank() }?.let { put("test_event_code", it) }
        }
        val json = objectMapper.writeValueAsString(payload)
        val url = "${META_GRAPH_BASE}/${config.metaPixelId}/events?access_token=${config.metaAccessToken}"
        val response = http.post()
            .uri(url)
            .header("Content-Type", "application/json")
            .body(json)
            .retrieve()
            .toEntity(String::class.java)
        return HttpOutcome(response.statusCode.value(), response.body?.take(2000), sha256(json))
    }

    /**
     * Google via sGTM: POST {sgtmUrl}/mp/collect?measurement_id=...&api_secret=
     * Payload Measurement Protocol GA4 (purchase). SEM PII no corpo — o sGTM faz o
     * enriquecimento server-side. client_id usa o orderId (estavel e anonimo).
     */
    private fun sendGoogle(config: TenantConfig, orderId: UUID, valueReais: Double): HttpOutcome {
        val payload = mapOf(
            "client_id" to orderId.toString(),
            "events" to listOf(
                mapOf(
                    "name" to "purchase",
                    "params" to mapOf(
                        "currency" to "BRL",
                        "value" to valueReais,
                        "transaction_id" to orderId.toString(),
                    ),
                ),
            ),
        )
        val json = objectMapper.writeValueAsString(payload)
        val base = config.googleSgtmUrl!!.trimEnd('/')
        val measurementId = config.googleMeasurementId ?: ""
        val url = "$base/mp/collect?measurement_id=$measurementId&api_secret="
        val response = http.post()
            .uri(url)
            .header("Content-Type", "application/json")
            .body(json)
            .retrieve()
            .toEntity(String::class.java)
        return HttpOutcome(response.statusCode.value(), response.body?.take(2000), sha256(json))
    }

    // ---------------------------------------------------------------------------
    // Atualizacao de estado (sempre dentro de transacao).
    // ---------------------------------------------------------------------------

    /** Grava o resultado de uma tentativa: incrementa attempts e decide o status final. */
    private fun recordResult(id: UUID, success: Boolean, code: Int?, body: String?, payloadHash: String?) {
        val d = conversionDispatchRepository.findById(id).orElse(null) ?: return
        d.attempts += 1
        d.lastAttemptAt = Instant.now()
        d.responseCode = code
        d.responseBody = body?.take(2000)
        if (payloadHash != null) d.payloadHash = payloadHash
        d.status = when {
            success -> { d.sentAt = Instant.now(); ConversionStatus.SENT }
            d.attempts >= MAX_ATTEMPTS -> ConversionStatus.SKIPPED
            else -> ConversionStatus.FAILED
        }
        conversionDispatchRepository.save(d)
    }

    /** Marca SKIPPED um despacho que ja esgotou as tentativas (sem nova tentativa). */
    private fun markSkipped(id: UUID) {
        conversionDispatchRepository.findById(id).orElse(null)?.let {
            if (it.status != ConversionStatus.SENT) {
                it.status = ConversionStatus.SKIPPED
                conversionDispatchRepository.save(it)
            }
        }
    }

    /** Backoff linear: lastAttemptAt + attempts*5min < now. Sem tentativa previa -> elegivel. */
    private fun backoffElapsed(dispatch: ConversionDispatch, now: Instant): Boolean {
        val last = dispatch.lastAttemptAt ?: return true
        val nextEligible = last.plus(dispatch.attempts.toLong() * BACKOFF_MINUTES, ChronoUnit.MINUTES)
        return nextEligible.isBefore(now)
    }

    // ---------------------------------------------------------------------------
    // Listagem (painel admin).
    // ---------------------------------------------------------------------------

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(status: ConversionStatus?, pageable: Pageable): Page<ConversionDispatchResponse> {
        val page = if (status != null) {
            conversionDispatchRepository.findByStatus(status, pageable)
        } else {
            conversionDispatchRepository.findAll(pageable)
        }
        return page.map { ConversionDispatchResponse.from(it) }
    }

    // ---------------------------------------------------------------------------
    // Hashing de PII (publico para teste; nunca recebe/loga telefone em claro alem daqui).
    // ---------------------------------------------------------------------------

    /** SHA-256 em hexadecimal minusculo (64 chars). */
    fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Normaliza o telefone para E.164 sem '+' (lowercase) e hasheia (formato exigido pela Meta). */
    fun hashPhone(phone: String): String {
        val normalized = phone.trim().trimStart('+').lowercase()
        return sha256(normalized)
    }

    companion object {
        const val META_GRAPH_BASE = "https://graph.facebook.com/v18.0"
        const val HTTP_TIMEOUT_MS = 5_000
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MINUTES = 5L
    }
}

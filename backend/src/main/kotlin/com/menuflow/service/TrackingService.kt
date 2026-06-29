package com.menuflow.service

import com.menuflow.dto.TrackingLinkCreateRequest
import com.menuflow.dto.TrackingLinkResponse
import com.menuflow.dto.TrackingLinkUpdateRequest
import com.menuflow.dto.TrackingRedirectResponse
import com.menuflow.dto.TrackingSummaryResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.MarketingEvent
import com.menuflow.model.MarketingEventType
import com.menuflow.model.TrackingLink
import com.menuflow.repository.tenant.MarketingEventRepository
import com.menuflow.repository.tenant.TrackingLinkRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Tracking first-party (Fase 3.6). O dono cria links rastreaveis com origem/midia/
 * campanha; o sistema gera um slug curto e a URL de destino com UTM. Cada clique vira
 * um marketing_event CLICK (com IP anonimizado), cada pedido fechado com o link vira um
 * CONVERSION com a receita. O painel ROAS agrega tudo por link. Tudo no banco do TENANT
 * (db-per-tenant) — sem cookies de terceiros, a atribuicao e dona do dado.
 */
@Service
class TrackingService(
    private val trackingLinkRepository: TrackingLinkRepository,
    private val marketingEventRepository: MarketingEventRepository,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
    @Value("\${menuflow.tracking.share-base-url:https://menuflow.duckdns.org/r}")
    private val shareBaseUrl: String,
    @Value("\${menuflow.tracking.destination-base-url:https://menuflow.duckdns.org/cardapio}")
    private val destinationBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(txManager)

    // ----------------------------- CRUD (ADMIN/MANAGER) -----------------------------

    @Transactional("tenantTransactionManager")
    fun create(req: TrackingLinkCreateRequest): TrackingLinkResponse {
        val slug = generateUniqueSlug()
        val source = req.source.trim()
        val medium = req.medium?.trim()?.ifBlank { null }
        val campaign = req.campaign?.trim()?.ifBlank { null }
        val saved = trackingLinkRepository.save(
            TrackingLink(
                slug = slug,
                name = req.name.trim(),
                source = source,
                medium = medium,
                campaign = campaign,
                destinationUrl = buildDestinationUrl(slug, source, medium, campaign),
            ),
        )
        return toResponse(saved)
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(pageable: Pageable): Page<TrackingLinkResponse> =
        trackingLinkRepository.findAll(pageable).map { toResponse(it) }

    /** PATCH parcial: so name e/ou active; nulos sao ignorados (nao limpam). */
    @Transactional("tenantTransactionManager")
    fun update(id: UUID, req: TrackingLinkUpdateRequest): TrackingLinkResponse {
        val link = load(id)
        req.name?.trim()?.takeIf { it.isNotBlank() }?.let { link.name = it }
        req.active?.let { link.active = it }
        return toResponse(trackingLinkRepository.save(link))
    }

    /** Soft-delete: apenas desativa (active=false); preserva o historico de eventos. */
    @Transactional("tenantTransactionManager")
    fun deactivate(id: UUID) {
        val link = load(id)
        link.active = false
        trackingLinkRepository.save(link)
    }

    // ----------------------------- Clique (publico) -----------------------------

    /**
     * Registra um clique no link e devolve os dados de redirecionamento. Slug
     * inexistente ou link inativo -> 404 (nao vaza existencia de link desativado).
     * O IP e anonimizado antes de persistir e o user-agent e truncado em 500 chars.
     */
    @Transactional("tenantTransactionManager")
    fun recordClick(slug: String, ip: String?, userAgent: String?): TrackingRedirectResponse {
        val link = trackingLinkRepository.findBySlug(slug)
            ?: throw ResourceNotFoundException("Link nao encontrado")
        if (!link.active) throw ResourceNotFoundException("Link nao encontrado")

        marketingEventRepository.save(
            MarketingEvent(
                trackingLink = link,
                eventType = MarketingEventType.CLICK,
                customerIp = anonymizeIp(ip),
                userAgent = userAgent?.take(500),
            ),
        )
        // Incremento atomico (nao ler-somar-salvar): nao perde cliques concorrentes.
        trackingLinkRepository.incrementClickCount(link.id!!)

        return TrackingRedirectResponse(
            destinationUrl = link.destinationUrl,
            slug = link.slug,
            source = link.source,
            medium = link.medium,
            campaign = link.campaign,
        )
    }

    // ----------------------------- Conversao (do create do pedido) -----------------------------

    /**
     * Registra a conversao de um pedido vinculado a um link. Chamado por
     * OrderService.create quando o request traz trackingLinkId.
     *
     * NUNCA pode derrubar a criacao do pedido. Como marketing_events.order_id referencia
     * orders(id), o pedido precisa estar comitado/visivel — entao, havendo transacao
     * ativa (fluxo real do create), a insercao e registrada para DEPOIS do commit
     * (AFTER_COMMIT) em transacao propria; sem transacao ativa (teste service-level com
     * pedido ja comitado), insere direto. Em ambos os casos e idempotente (1 CONVERSION
     * por pedido) e fail-safe (try/catch). Mesmo padrao de CartRecoveryService.onOrderCreated.
     */
    fun recordConversion(orderId: UUID, trackingLinkId: UUID, revenueCents: Long) {
        val slug = TenantContext.get() ?: return
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        recordConversionSafely(slug, orderId, trackingLinkId, revenueCents)
                    }
                },
            )
        } else {
            recordConversionSafely(slug, orderId, trackingLinkId, revenueCents)
        }
    }

    private fun recordConversionSafely(slug: String, orderId: UUID, trackingLinkId: UUID, revenueCents: Long) {
        val previous = TenantContext.get()
        TenantContext.set(slug)
        try {
            txTemplate.execute {
                // Idempotencia: nao duplica o CONVERSION em reentrega/duplo clique.
                if (marketingEventRepository.existsByOrderIdAndEventType(orderId, MarketingEventType.CONVERSION)) {
                    return@execute
                }
                val link = trackingLinkRepository.findById(trackingLinkId).orElse(null) ?: return@execute
                marketingEventRepository.save(
                    MarketingEvent(
                        trackingLink = link,
                        eventType = MarketingEventType.CONVERSION,
                        orderId = orderId,
                        revenueCents = revenueCents,
                    ),
                )
            }
        } catch (e: Exception) {
            // Fail-safe: a atribuicao nunca pode derrubar/afetar o pedido ja criado.
            log.warn("Falha ao registrar conversao do pedido {} (link {}): {}", orderId, trackingLinkId, e.message)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    // ----------------------------- Painel ROAS -----------------------------

    /**
     * Resumo por link na janela [from, to]. from/to nulos => ultimos 30 dias.
     * conversionRate e NaN-safe (0.0 quando nao ha cliques).
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun getSummary(from: Instant?, to: Instant?): List<TrackingSummaryResponse> {
        val end = to ?: Instant.now()
        val start = from ?: end.minus(30, ChronoUnit.DAYS)
        return marketingEventRepository.summaryBetween(start, end).map { row ->
            val clicks = (row[4] as Number).toLong()
            val conversions = (row[5] as Number).toLong()
            TrackingSummaryResponse(
                trackingLinkId = row[0] as UUID,
                name = row[1] as String,
                source = row[2] as String,
                slug = row[3] as String,
                clicks = clicks,
                conversions = conversions,
                revenueCents = (row[6] as Number).toLong(),
                conversionRate = if (clicks > 0) conversions.toDouble() / clicks else 0.0,
            )
        }
    }

    // ----------------------------- Helpers -----------------------------

    private fun load(id: UUID): TrackingLink =
        trackingLinkRepository.findById(id).orElseThrow { ResourceNotFoundException("Link nao encontrado") }

    private fun toResponse(t: TrackingLink) = TrackingLinkResponse.from(t, "$shareBaseUrl/${t.slug}")

    /** Slug aleatorio de 8 chars [a-z0-9]; tenta de novo se colidir (36^8 ~ 2.8 tri). */
    private fun generateUniqueSlug(): String {
        repeat(10) {
            val slug = (1..8).map { SLUG_CHARS.random() }.joinToString("")
            if (!trackingLinkRepository.existsBySlug(slug)) return slug
        }
        throw BusinessException("Nao foi possivel gerar um slug unico; tente novamente")
    }

    /** Monta a URL de destino com os parametros UTM (valores URL-encoded). */
    private fun buildDestinationUrl(slug: String, source: String, medium: String?, campaign: String?): String =
        buildString {
            append(destinationBaseUrl)
            append("?src=").append(enc(slug))
            append("&utm_source=").append(enc(source))
            medium?.let { append("&utm_medium=").append(enc(it)) }
            campaign?.let { append("&utm_campaign=").append(enc(it)) }
        }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private const val SLUG_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        /**
         * Anonimiza o IP do cliente (LGPD/privacidade): IPv4 zera o ultimo octeto
         * (192.168.1.100 -> 192.168.1.0); IPv6 mantem so os primeiros 64 bits (4
         * hextets) e zera o resto. null/vazio/forma invalida -> null. Nunca persistir
         * nem logar o IP completo.
         */
        fun anonymizeIp(raw: String?): String? {
            val ip = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                "." in ip && ":" !in ip -> {
                    val parts = ip.split(".")
                    if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.0" else null
                }
                ":" in ip -> {
                    // Mantem ate 4 hextets nao-vazios e termina com "::" (resto zerado).
                    val head = ip.split(":").filter { it.isNotEmpty() }.take(4)
                    (head + listOf("", "")).joinToString(":")
                }
                else -> null
            }
        }
    }
}

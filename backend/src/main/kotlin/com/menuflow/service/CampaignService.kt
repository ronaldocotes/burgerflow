package com.menuflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.dto.CampaignCreateRequest
import com.menuflow.dto.CampaignResponse
import com.menuflow.dto.CampaignSendResponse
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.Campaign
import com.menuflow.model.CampaignSegment
import com.menuflow.model.CampaignSend
import com.menuflow.model.CampaignStatus
import com.menuflow.model.Customer
import com.menuflow.model.RfvScore
import com.menuflow.model.RfvSegment
import com.menuflow.model.SendStatus
import com.menuflow.repository.tenant.CampaignRepository
import com.menuflow.repository.tenant.CampaignSendRepository
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Campanhas de marketing por WhatsApp (Fase 3.4). Cria a campanha em DRAFT ja com a
 * lista de destinatarios pre-calculada (CampaignSend QUEUED + mensagem interpolada),
 * dispara o envio em segundo plano (CampaignDispatcher) e trata opt-in/opt-out.
 *
 * Seguranca anti-ban (WAHA usa API nao-oficial): so entram na campanha clientes com
 * opt-in de marketing explicito; o disparo respeita delay aleatorio e limite diario
 * (ver CampaignDispatcher e tenant_config). Tudo no banco do TENANT (db-per-tenant).
 */
@Service
class CampaignService(
    private val campaignRepository: CampaignRepository,
    private val campaignSendRepository: CampaignSendRepository,
    private val customerRepository: CustomerRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val rfvService: RfvService,
    private val campaignDispatcher: CampaignDispatcher,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Destinatario resolvido: cliente + score RFV (quando ele ja comprou). */
    data class Recipient(val customer: Customer, val rfvScore: RfvScore?)

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(pageable: Pageable): Page<CampaignResponse> =
        campaignRepository.findAllByOrderByCreatedAtDesc(pageable).map { CampaignResponse.from(it) }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(id: UUID): CampaignResponse =
        CampaignResponse.from(load(id))

    @Transactional("tenantTransactionManager", readOnly = true)
    fun listSends(id: UUID, pageable: Pageable): Page<CampaignSendResponse> {
        load(id) // 404 se a campanha nao existe
        return campaignSendRepository.findByCampaignId(id, pageable).map { CampaignSendResponse.from(it) }
    }

    /**
     * Cria a campanha em DRAFT e pre-calcula os envios (CampaignSend QUEUED) com a
     * mensagem ja interpolada por destinatario. total_recipients = nro de envios.
     */
    @Transactional("tenantTransactionManager")
    fun create(req: CampaignCreateRequest): CampaignResponse {
        val dailyLimit = currentDailyLimit()
        val campaign = campaignRepository.save(
            Campaign(
                name = req.name.trim(),
                messageTemplate = req.messageTemplate,
                segment = req.segment,
                segmentParams = req.segmentParams?.let { objectMapper.writeValueAsString(it) },
                status = CampaignStatus.DRAFT,
                scheduledAt = req.scheduledAt,
            ),
        )

        val recipients = buildRecipients(req.segment, dailyLimit)
        val sends = recipients.map { r ->
            CampaignSend(
                campaignId = campaign.id!!,
                customerId = r.customer.id!!,
                phone = r.customer.phoneNumber,
                message = interpolate(req.messageTemplate, r.customer, r.rfvScore),
                status = SendStatus.QUEUED,
            )
        }
        campaignSendRepository.saveAll(sends)
        campaign.totalRecipients = sends.size
        return CampaignResponse.from(campaignRepository.save(campaign))
    }

    /**
     * Calcula os destinatarios elegiveis do segmento, ja filtrando por opt-in de
     * marketing e respeitando o limite diario. Clientes sem opt-in NUNCA entram.
     */
    fun buildRecipients(segment: CampaignSegment, dailyLimit: Int): List<Recipient> {
        val optIn = customerRepository.findByMarketingOptInTrueAndActiveTrue()
        if (optIn.isEmpty()) return emptyList()

        val scoreMap = rfvService.scoreMap()
        val filtered = when (segment) {
            CampaignSegment.ALL_OPT_IN,
            CampaignSegment.CUSTOM,
            -> optIn // CUSTOM: placeholder = todos com opt-in (segmentacao fina e follow-up)
            CampaignSegment.RFV_INACTIVE -> optIn.filter { scoreMap[it.id]?.segment == RfvSegment.INACTIVE }
            CampaignSegment.RFV_AT_RISK -> optIn.filter { scoreMap[it.id]?.segment == RfvSegment.AT_RISK }
            CampaignSegment.RFV_LOYAL -> optIn.filter { scoreMap[it.id]?.segment == RfvSegment.LOYAL }
        }
        return filtered.take(dailyLimit.coerceAtLeast(0)).map { Recipient(it, scoreMap[it.id]) }
    }

    /**
     * Substitui as variaveis do template ({nome}, {pontos}, {dias}) e adiciona um
     * emoji aleatorio no inicio (spintax simples: variacao leve por destinatario para
     * reduzir deteccao de mensagem em massa pelo WhatsApp).
     */
    fun interpolate(template: String, customer: Customer, rfvScore: RfvScore?): String {
        val body = template
            .replace("{nome}", customer.name)
            .replace("{pontos}", customer.loyaltyPoints.toString())
            .replace("{dias}", (rfvScore?.recencyDays ?: 0).toString())
        return "${EMOJIS.random()} $body"
    }

    /**
     * Dispara a campanha. Marca a intencao e delega o envio ao dispatcher ASSINCRONO
     * (delay anti-ban roda fora do thread HTTP). So DRAFT/PAUSED podem iniciar.
     */
    @Transactional("tenantTransactionManager")
    fun start(id: UUID, tenantSlug: String): CampaignResponse {
        val campaign = load(id)
        if (campaign.status !in listOf(CampaignStatus.DRAFT, CampaignStatus.PAUSED)) {
            throw ConflictException("Campanha nao pode ser iniciada no estado ${campaign.status}")
        }
        // O dispatcher (async) recarrega e marca RUNNING; aqui so validamos o estado.
        campaignDispatcher.dispatchAsync(tenantSlug, id)
        return CampaignResponse.from(campaign)
    }

    /** Pausa uma campanha em andamento. O loop de despacho aborta na proxima iteracao. */
    @Transactional("tenantTransactionManager")
    fun pause(id: UUID): CampaignResponse {
        val campaign = load(id)
        if (campaign.status == CampaignStatus.RUNNING) {
            campaign.status = CampaignStatus.PAUSED
            campaignRepository.save(campaign)
        }
        return CampaignResponse.from(campaign)
    }

    /** Concede opt-in de marketing a um cliente (operador). */
    @Transactional("tenantTransactionManager")
    fun grantOptIn(customerId: UUID) {
        val customer = customerRepository.findById(customerId)
            .orElseThrow { ResourceNotFoundException("Cliente nao encontrado: $customerId") }
        customer.marketingOptIn = true
        customer.optInAt = Instant.now()
        customer.optOutAt = null
        customerRepository.save(customer)
    }

    /** Revoga o opt-in de marketing de um cliente (operador). */
    @Transactional("tenantTransactionManager")
    fun revokeOptInById(customerId: UUID) {
        val customer = customerRepository.findById(customerId)
            .orElseThrow { ResourceNotFoundException("Cliente nao encontrado: $customerId") }
        applyOptOut(customer)
    }

    /**
     * Opt-out por telefone (chamado pelo endpoint publico de descadastro). Idempotente
     * e silencioso: telefone sem cliente nao lanca (nao vaza existencia).
     */
    @Transactional("tenantTransactionManager")
    fun optOutByPhone(phone: String) {
        val digits = phone.replace(Regex("[^0-9]"), "")
        if (digits.isEmpty()) return
        val customer = customerRepository.findByPhoneNumber(phone)
            ?: customerRepository.findByPhoneNumber(digits)
            ?: run {
                log.info("Opt-out de telefone sem cliente cadastrado (ignorado)")
                return
            }
        applyOptOut(customer)
    }

    private fun applyOptOut(customer: Customer) {
        customer.marketingOptIn = false
        customer.optOutAt = Instant.now()
        customerRepository.save(customer)
        // Cancela envios ainda em fila desse cliente (em qualquer campanha).
        val queued = campaignSendRepository.findByCustomerIdAndStatus(customer.id!!, SendStatus.QUEUED)
        queued.forEach { it.status = SendStatus.OPT_OUT }
        campaignSendRepository.saveAll(queued)
    }

    private fun load(id: UUID): Campaign =
        campaignRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Campanha nao encontrada: $id") }

    private fun currentDailyLimit(): Int =
        tenantConfigRepository.findFirstByOrderByCreatedAtAsc()?.campaignDailyLimit ?: DEFAULT_DAILY_LIMIT

    companion object {
        /** Spintax simples: 1 emoji do tema food no inicio de cada mensagem. */
        val EMOJIS = listOf("🍔", "🍕", "🌮", "🥤", "🍟")

        /** Default quando o tenant nao tem config (fallback de seguranca). */
        const val DEFAULT_DAILY_LIMIT = 50
    }
}

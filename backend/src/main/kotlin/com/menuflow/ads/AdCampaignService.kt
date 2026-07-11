package com.menuflow.ads

import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.exception.ServiceUnavailableException
import com.menuflow.exception.TooManyRequestsException
import com.menuflow.ifood.IfoodTokenCipher
import com.menuflow.model.AdAccountStatus
import com.menuflow.model.AdCampaign
import com.menuflow.model.AdCampaignStatus
import com.menuflow.model.AdCreative
import com.menuflow.platform.ModuleKey
import com.menuflow.platform.ModuleGateService
import com.menuflow.repository.tenant.AdAccountRepository
import com.menuflow.repository.tenant.AdCampaignRepository
import com.menuflow.repository.tenant.AdCreativeRepository
import com.menuflow.repository.tenant.ProductRepository
import com.menuflow.security.SecurityUtils
import com.menuflow.security.ratelimit.AdsWriteRateLimiter
import com.menuflow.service.AuditLogService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Criar / pausar / ativar campanha de Meta Ads (Fase 8.2). Esta e a fase que GASTA DINHEIRO
 * REAL e cria objetos de verdade na conta da Meta do restaurante. Tudo no banco do TENANT
 * (db-per-tenant), isolado por restaurante.
 *
 * NUCLEO (nao relaxar):
 *  1. Verba: piso R$10,00 (1000 centavos) e teto = entitlement do tenant
 *     (tenant_module.limits_json -> max_daily_budget_cents, lido via ModuleGateService).
 *     Sem teto configurado E sem env default => FAIL-CLOSED (nao cria). Acima do teto =>
 *     rejeita (nunca clampa em silencio). O teto e REVALIDADO tambem na ativacao.
 *  2. Campanha NASCE PAUSED. Ativar e endpoint separado e auditado.
 *  3. Idempotencia: Idempotency-Key + UNIQUE(ad_account_id, idempotency_key) impedem 2
 *     campanhas do mesmo request. Retry com a mesma chave devolve a existente.
 *  4. Falha parcial (saga campaign->adset->creative->ad): compensacao apaga a campanha na
 *     Meta (cascateia adset/ad/creative) e remove a reserva local — sem lixo, sem gasto
 *     (tudo nasce PAUSED, risco de spend e zero por construcao).
 *
 * O token da conta e decifrado so em memoria para a chamada; NUNCA vai a log/DTO/erro.
 */
@Service
class AdCampaignService(
    private val adAccountRepository: AdAccountRepository,
    private val campaignRepository: AdCampaignRepository,
    private val creativeRepository: AdCreativeRepository,
    private val productRepository: ProductRepository,
    private val metaGraphClient: MetaGraphClient,
    private val cipher: IfoodTokenCipher,
    private val moduleGateService: ModuleGateService,
    private val auditService: AuditLogService,
    private val rateLimiter: AdsWriteRateLimiter,
    @Qualifier("tenantTransactionManager") txManager: PlatformTransactionManager,
    // Teto default fail-closed via env MENUFLOW_ADS_DEFAULT_MAX_DAILY_BUDGET_CENTS.
    // Ausente (null) => sem teto por omissao: a criacao e BLOQUEADA (nao vira ilimitado).
    @Value("\${menuflow.ads.default-max-daily-budget-cents:#{null}}")
    private val defaultMaxDailyBudgetCents: Long?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // TransactionTemplate (nao @Transactional nos proprios metodos) para manter o HTTP da
    // Meta FORA da transacao do Postgres e evitar self-invocation (mesmo padrao do modulo).
    private val txTemplate = TransactionTemplate(txManager)

    /** Piso de verba diaria em centavos (R$10,00). Calibrado para BRL; ver nota no DoD. */
    private val minDailyBudgetCents = 1000L

    /**
     * Cria a campanha (nasce PAUSED). Saga de 4 escritas externas com compensacao. Retry com
     * o mesmo Idempotency-Key devolve a campanha existente (nao recria/gasta de novo).
     */
    fun create(req: CreateAdCampaignRequest, idempotencyKey: String): AdCampaignResponse {
        val principal = SecurityUtils.currentPrincipalOrThrow()
        val key = idempotencyKey.trim()
        if (key.isBlank()) throw BusinessException("Idempotency-Key obrigatorio para criar campanha.")

        // Rate-limit da escrita que gasta verba (por tenant). Antes de qualquer efeito.
        if (!rateLimiter.tryAcquire(principal.tenantSlug)) {
            throw TooManyRequestsException("Muitas criacoes de campanha em pouco tempo. Aguarde um instante e tente de novo.")
        }

        val accountId = req.accountId ?: throw BusinessException("accountId obrigatorio.")
        val account = adAccountRepository.findById(accountId)
            .orElseThrow { ResourceNotFoundException("Conta de anuncio nao encontrada: $accountId") }

        // Idempotencia fast-path: chave ja usada nesta conta -> devolve a campanha existente.
        campaignRepository.findByAdAccountIdAndIdempotencyKey(accountId, key)
            ?.let { return AdCampaignResponse.from(it) }

        // Pre-requisitos da conta.
        if (account.status != AdAccountStatus.CONNECTED) {
            throw BusinessException("A conta de anuncio nao esta conectada (status ${account.status}). Reconecte antes de criar campanha.")
        }
        if (account.pageId.isNullOrBlank()) {
            throw BusinessException(
                "Configure uma Pagina do Facebook nesta conta antes de criar campanha " +
                    "(liste em GET /ads/accounts/{id}/pages e grave em PUT /ads/accounts/{id}/page).",
            )
        }

        // Validacoes de dominio. Range de lat/lng aqui (bean-validation nao cobre Double).
        val lat = req.geoLat ?: throw BusinessException("geoLat obrigatorio.")
        val lng = req.geoLng ?: throw BusinessException("geoLng obrigatorio.")
        val radius = req.radiusKm ?: throw BusinessException("radiusKm obrigatorio.")
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            throw BusinessException("Coordenadas geograficas invalidas.")
        }
        val budget = req.dailyBudgetCents ?: throw BusinessException("dailyBudgetCents obrigatorio.")
        validateBudget(budget, principal.tenantUuid)

        // Produto p/ imagem (opcional): precisa existir no tenant corrente (anti-IDOR natural).
        val product = req.productId?.let {
            productRepository.findById(it)
                .orElseThrow { ResourceNotFoundException("Produto nao encontrado: $it") }
        }
        // Guard anti-SSRF ANTES da saga: imageUrl e string LIVRE do cliente (ProductDtos/
        // ProductService) e sera BAIXADA e reenviada a Meta. URL insegura (nao-https ou host
        // que resolve para IP interno, ex.: 169.254.169.254/127.0.0.1/10.x) => 400 e NADA e
        // criado/baixado. Falha fechado aqui, antes de qualquer efeito externo ou reserva.
        product?.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            try {
                MetaGraphClient.assertSafeImageUrl(url)
            } catch (e: IllegalArgumentException) {
                throw BusinessException(e.message ?: "URL de imagem do produto invalida.")
            }
        }

        // RESERVA idempotente (tx curta e COMMITADA): a UNIQUE(conta, chave) barra duplicata
        // concorrente. Se dois requests com a mesma chave correrem, um vence e o outro recebe
        // a violacao -> devolvemos o vencedor.
        val reserved = try {
            txTemplate.execute {
                campaignRepository.save(
                    AdCampaign(
                        adAccountId = accountId,
                        name = req.name,
                        dailyBudgetCents = budget,
                        idempotencyKey = key,
                        status = AdCampaignStatus.DRAFT,
                        geoLat = lat,
                        geoLng = lng,
                        radiusKm = radius,
                        trackingLinkId = req.trackingLinkId,
                        createdByUserId = principal.userId,
                    ),
                )
            }!!
        } catch (e: DataIntegrityViolationException) {
            return campaignRepository.findByAdAccountIdAndIdempotencyKey(accountId, key)
                ?.let { AdCampaignResponse.from(it) }
                ?: throw ConflictException("Criacao concorrente da mesma campanha; tente novamente.")
        }

        val reservedId = reserved.id!!
        val token = cipher.decrypt(account.tokenEnc, account.tokenIv)
        var externalCampaignId: String? = null
        try {
            externalCampaignId = metaGraphClient.createCampaign(
                token, account.externalAccountId, req.name, reserved.objective,
            )
            val adsetId = metaGraphClient.createAdSet(
                token, account.externalAccountId, externalCampaignId,
                "${req.name} - conjunto", budget, lat, lng, radius,
            )
            // Imagem do anuncio a partir da foto do catalogo (opcional). Falha no upload nao
            // derruba a campanha: seguimos link-only (a Meta pode recusar; ver HIPOTESE no DoD).
            val imageHash = product?.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                runCatching { metaGraphClient.uploadAdImage(token, account.externalAccountId, url) }
                    .onFailure { log.warn("[ads] upload da imagem falhou; criativo link-only: {}", it.message) }
                    .getOrNull()
            }
            val creativeId = metaGraphClient.createAdCreative(
                token, account.externalAccountId, account.pageId!!,
                "${req.name} - criativo", req.primaryText, req.destinationUrl, imageHash,
            )
            val adId = metaGraphClient.createAd(
                token, account.externalAccountId, "${req.name} - anuncio", adsetId, creativeId,
            )

            // PERSISTE o sucesso (tx curta). Campanha NASCE PAUSED.
            val saved = txTemplate.execute {
                val c = campaignRepository.findById(reservedId)
                    .orElseThrow { ResourceNotFoundException("Campanha reservada sumiu: $reservedId") }
                c.externalCampaignId = externalCampaignId
                c.externalAdsetId = adsetId
                c.externalAdId = adId
                c.status = AdCampaignStatus.PAUSED
                campaignRepository.save(c)
                creativeRepository.save(
                    AdCreative(
                        campaignId = c.id!!,
                        primaryText = req.primaryText,
                        headline = req.headline,
                        cta = req.cta,
                        productId = product?.id,
                        imageHash = imageHash,
                    ),
                )
                auditService.log(
                    action = "AD_CAMPAIGN_CREATE",
                    entity = "ad_campaign",
                    entityId = c.id,
                    after = mapOf(
                        "externalCampaignId" to externalCampaignId,
                        "dailyBudgetCents" to budget,
                        "status" to c.status.name,
                    ),
                    actorUserId = principal.userId,
                )
                c
            }!!
            log.info("[ads] campanha criada (PAUSED) ext={} tenant={}", externalCampaignId, principal.tenantSlug)
            return AdCampaignResponse.from(saved)
        } catch (e: Exception) {
            compensate(reservedId, externalCampaignId, token, principal.userId, e)
            throw translateMetaError(e)
        }
    }

    /**
     * Compensacao de falha parcial: apaga a campanha na Meta (DELETE cascateia adset/ad/
     * creative) e remove a reserva local — nada fica orfao/ACTIVE. Auditamos o estado.
     */
    private fun compensate(reservedId: UUID, externalCampaignId: String?, token: String, actorUserId: UUID, cause: Exception) {
        if (externalCampaignId != null) {
            runCatching { metaGraphClient.deleteObject(token, externalCampaignId) }
                .onFailure { log.error("[ads] compensacao: falha ao apagar campanha externa {} na Meta: {}", externalCampaignId, it.message) }
        }
        try {
            txTemplate.execute {
                campaignRepository.findById(reservedId).ifPresent { campaignRepository.delete(it) }
                auditService.log(
                    action = "AD_CAMPAIGN_CREATE_FAILED",
                    entity = "ad_campaign",
                    entityId = reservedId,
                    reason = cause.message?.take(300),
                    actorUserId = actorUserId,
                )
            }
        } catch (e: Exception) {
            log.error("[ads] compensacao: falha ao remover reserva local {}: {}", reservedId, e.message)
        }
    }

    /** Pausa a campanha na Meta e espelha o estado localmente. */
    fun pause(id: UUID): AdCampaignResponse = transition(id, AdCampaignStatus.PAUSED, "PAUSED", "AD_CAMPAIGN_PAUSE", revalidateBudget = false)

    /**
     * Ativa a campanha. REVALIDA o teto de verba (o teto pode ter mudado desde a criacao, ou
     * a campanha foi criada com verba que hoje excede). Rate-limited como escrita que gasta.
     */
    fun activate(id: UUID): AdCampaignResponse = transition(id, AdCampaignStatus.ACTIVE, "ACTIVE", "AD_CAMPAIGN_ACTIVATE", revalidateBudget = true)

    private fun transition(
        id: UUID,
        newStatus: AdCampaignStatus,
        metaStatus: String,
        auditAction: String,
        revalidateBudget: Boolean,
    ): AdCampaignResponse {
        val principal = SecurityUtils.currentPrincipalOrThrow()
        if (!rateLimiter.tryAcquire(principal.tenantSlug)) {
            throw TooManyRequestsException("Muitas operacoes de campanha em pouco tempo. Aguarde um instante.")
        }
        val campaign = campaignRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Campanha nao encontrada: $id") }
        val ext = campaign.externalCampaignId
            ?: throw BusinessException("Campanha ainda nao foi criada na Meta.")

        if (revalidateBudget) validateBudget(campaign.dailyBudgetCents, principal.tenantUuid)

        val account = adAccountRepository.findById(campaign.adAccountId)
            .orElseThrow { ResourceNotFoundException("Conta de anuncio nao encontrada: ${campaign.adAccountId}") }
        val token = cipher.decrypt(account.tokenEnc, account.tokenIv)

        try {
            if (newStatus == AdCampaignStatus.ACTIVE) {
                // Como adset e ad NASCEM PAUSED (nada roda por construcao), ativar exige flipar os
                // TRES. Ordem: filhos PRIMEIRO, campanha por ULTIMO. Enquanto a campanha seguir
                // PAUSED nada entrega (pausar a campanha e o gate forte e conhecido da hierarquia),
                // entao se qualquer flip falhar no meio abortamos com a campanha ainda PAUSED e o
                // status LOCAL inalterado: fail-closed, gasto zero. Retry re-flipa (idempotente).
                campaign.externalAdsetId?.let { metaGraphClient.updateStatus(token, it, "ACTIVE") }
                campaign.externalAdId?.let { metaGraphClient.updateStatus(token, it, "ACTIVE") }
                metaGraphClient.updateStatus(token, ext, "ACTIVE")
            } else {
                // Pausar a campanha ja basta para parar toda a entrega (nao precisa tocar filhos).
                metaGraphClient.updateStatus(token, ext, metaStatus)
            }
        } catch (e: Exception) {
            throw translateMetaError(e)
        }
        val effective = runCatching { metaGraphClient.fetchCampaignEffectiveStatus(token, ext) }.getOrNull()

        return try {
            txTemplate.execute {
                val fresh = campaignRepository.findById(id)
                    .orElseThrow { ResourceNotFoundException("Campanha nao encontrada: $id") }
                fresh.status = newStatus
                fresh.effectiveStatus = effective
                campaignRepository.save(fresh)
                auditService.log(
                    action = auditAction,
                    entity = "ad_campaign",
                    entityId = fresh.id,
                    after = mapOf("status" to newStatus.name, "effectiveStatus" to effective),
                    actorUserId = principal.userId,
                )
                AdCampaignResponse.from(fresh)
            }!!
        } catch (e: Exception) {
            // DRIFT reconciliavel: a Meta JA foi flipada mas o commit local falhou. Nao engolimos —
            // registramos com os external ids para reconciliar. O disconnect NAO confia no status
            // local (bloqueia por external_campaign_id != null), entao mesmo com o local defasado a
            // conta/token nao pode ser solta enquanto a campanha existir na Meta.
            if (newStatus == AdCampaignStatus.ACTIVE) {
                log.error(
                    "[ads] DRIFT: campanha ext={} adset={} ad={} ATIVADA na Meta mas commit local falhou tenant={}: {}",
                    ext, campaign.externalAdsetId, campaign.externalAdId, principal.tenantSlug, e.message,
                )
                runCatching {
                    txTemplate.execute {
                        auditService.log(
                            action = "AD_CAMPAIGN_ACTIVATE_DRIFT",
                            entity = "ad_campaign",
                            entityId = id,
                            reason = "Meta ACTIVE mas commit local falhou: ${e.message?.take(200)}",
                            after = mapOf(
                                "externalCampaignId" to ext,
                                "externalAdsetId" to campaign.externalAdsetId,
                                "externalAdId" to campaign.externalAdId,
                            ),
                            actorUserId = principal.userId,
                        )
                    }
                }
            }
            throw e
        }
    }

    /** Lista as campanhas do tenant (mais recentes primeiro). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(): List<AdCampaignResponse> =
        campaignRepository.findAllByOrderByCreatedAtDesc().map { AdCampaignResponse.from(it) }

    /** Detalhe de uma campanha do tenant (404 se nao existe no banco corrente). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(id: UUID): AdCampaignResponse =
        campaignRepository.findById(id)
            .map { AdCampaignResponse.from(it) }
            .orElseThrow { ResourceNotFoundException("Campanha nao encontrada: $id") }

    /**
     * Valida a verba diaria: piso R$10,00; teto = entitlement do tenant
     * (tenant_module.limits_json -> max_daily_budget_cents) OU o env default; sem nenhum dos
     * dois => FAIL-CLOSED (bloqueia). Acima do teto => rejeita (nunca clampa).
     */
    private fun validateBudget(dailyBudgetCents: Long, tenantUuid: UUID) {
        if (dailyBudgetCents < minDailyBudgetCents) {
            throw BusinessException("Verba diaria minima e R$ 10,00 (voce informou R$ ${reais(dailyBudgetCents)}).")
        }
        val cap = moduleGateService.readLimit(tenantUuid, ModuleKey.ADS, "max_daily_budget_cents")
            ?: defaultMaxDailyBudgetCents
            ?: throw BusinessException("Teto de verba nao configurado para este restaurante — contate o suporte.")
        if (dailyBudgetCents > cap) {
            throw BusinessException("Verba diaria (R$ ${reais(dailyBudgetCents)}) acima do teto permitido (R$ ${reais(cap)}).")
        }
    }

    /** Centavos -> "R$ X,YY" (so para mensagem de erro; nao usa float). */
    private fun reais(cents: Long): String = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"

    /** Mapeia excecoes da Meta para respostas HTTP claras (o token nunca vaza na mensagem). */
    private fun translateMetaError(e: Exception): RuntimeException = when (e) {
        is BusinessException, is ResourceNotFoundException, is ConflictException, is TooManyRequestsException,
        is ServiceUnavailableException -> e as RuntimeException
        is MetaTokenInvalidException ->
            BusinessException("O token da Meta desta conta foi recusado. Reconecte a conta e tente de novo.")
        is MetaRateLimitException ->
            ServiceUnavailableException("A Meta limitou as chamadas agora. Tente novamente em instantes.")
        is MetaGraphException ->
            ServiceUnavailableException("Nao foi possivel falar com a Meta agora: ${e.message}")
        else -> BusinessException("Falha ao processar a campanha: ${e.message}")
    }
}

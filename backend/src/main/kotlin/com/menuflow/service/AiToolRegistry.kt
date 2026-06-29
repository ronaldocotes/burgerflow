package com.menuflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.client.AiTool
import com.menuflow.dto.CampaignCreateRequest
import com.menuflow.dto.CouponCreateRequest
import com.menuflow.model.CampaignSegment
import com.menuflow.model.CartSessionStatus
import com.menuflow.model.DiscountType
import com.menuflow.model.RfvSegment
import com.menuflow.repository.tenant.CartSessionRepository
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.LoyaltyRewardRepository
import com.menuflow.repository.tenant.LoyaltyTransactionRepository
import com.menuflow.repository.tenant.OrderItemRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Catalogo de ferramentas (tools) que o Copiloto (Fase 4.1) oferece ao LLM, com a
 * definicao JSON (formato OpenAI tools[]) e o handler Kotlin que executa a consulta/
 * acao real contra o banco do TENANT corrente.
 *
 * Seguranca:
 *  - Tudo roda no banco do tenant bound em TenantContext (db-per-tenant isola). O LLM
 *    NUNCA recebe SQL nem ids para escolher banco — so parametros de negocio.
 *  - Ferramentas de ACAO (create_coupon, schedule_campaign) exigem papel ADMIN; sem
 *    ele o handler devolve um resultado de erro (o LLM avisa o dono educadamente) em
 *    vez de executar — nunca lanca para nao derrubar a conversa.
 *  - Resultados sao JSON (string) consumidos pelo LLM como conteudo de mensagem 'tool'.
 */
@Service
class AiToolRegistry(
    private val dreService: DreService,
    private val rfvService: RfvService,
    private val couponService: CouponService,
    private val campaignService: CampaignService,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val customerRepository: CustomerRepository,
    private val cartSessionRepository: CartSessionRepository,
    private val loyaltyTransactionRepository: LoyaltyTransactionRepository,
    private val loyaltyRewardRepository: LoyaltyRewardRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of("America/Sao_Paulo")

    /** Definicoes das ferramentas enviadas ao LLM em todo turno (tools[] do OpenAI). */
    fun toolDefinitions(): List<AiTool> = listOf(
        function(
            "get_dre",
            "Retorna o DRE (receita, custos, lucro) do restaurante em um periodo.",
            mapOf(
                "period" to enumProp("Periodo do relatorio", listOf("current_month", "last_month", "last_7_days")),
            ),
            required = listOf("period"),
        ),
        function(
            "get_top_products",
            "Lista os produtos mais vendidos (com quantidade e receita) em um periodo.",
            mapOf(
                "limit" to intProp("Quantos produtos retornar (1-20)", 1, 20),
                "period" to enumProp("Periodo", listOf("current_month", "last_month", "last_7_days")),
            ),
        ),
        function(
            "get_rfv_summary",
            "Resumo da base de clientes por segmento RFV (fieis, em risco, inativos, novos).",
            emptyMap(),
        ),
        function(
            "get_recent_orders",
            "Ultimos pedidos com status, total e canal de venda.",
            mapOf("limit" to intProp("Quantos pedidos (1-50)", 1, 50)),
        ),
        function(
            "get_loyalty_stats",
            "Estatisticas do programa de fidelidade: clientes com pontos, pontos creditados e recompensas.",
            emptyMap(),
        ),
        function(
            "get_abandoned_carts",
            "Carrinhos abandonados de hoje por situacao (ativos, mensagens enviadas, recuperados).",
            emptyMap(),
        ),
        function(
            "get_customers_at_risk",
            "Clientes classificados como EM RISCO (recencia/frequencia caindo), com nome e telefone.",
            mapOf("limit" to intProp("Quantos clientes (1-50)", 1, 50)),
        ),
        function(
            "create_coupon",
            "Cria um cupom de desconto. Requer papel ADMIN.",
            mapOf(
                "code" to strProp("Codigo do cupom (ex: BLACK10)"),
                "type" to enumProp("Tipo de desconto", listOf("FIXED", "PERCENT")),
                "value" to numProp("Valor: em CENTAVOS para FIXED; percentual x100 para PERCENT (ex: 1500 = 15%)"),
                "maxUses" to intProp("Limite total de usos (opcional)", 1, 1_000_000),
                "description" to strProp("Descricao do cupom (opcional)"),
            ),
            required = listOf("code", "type", "value"),
        ),
        function(
            "schedule_campaign",
            "Cria uma campanha de WhatsApp em RASCUNHO (NAO dispara; o dono confirma no painel). Requer papel ADMIN.",
            mapOf(
                "name" to strProp("Nome da campanha"),
                "message" to strProp("Mensagem (use {nome} para o nome do cliente)"),
                "segment" to enumProp("Publico-alvo", listOf("ALL", "LOYAL", "AT_RISK", "INACTIVE")),
            ),
            required = listOf("name", "message", "segment"),
        ),
    )

    /**
     * Executa a ferramenta [name] com os [args] vindos do LLM. [actorUserId] e o dono
     * autenticado (ator das acoes/auditoria); [userRoles] decide a autorizacao das
     * ferramentas de acao. Retorna SEMPRE um JSON-string (resultado ou erro tratado).
     */
    fun execute(name: String, args: Map<String, Any?>, actorUserId: UUID?, userRoles: List<String>): String {
        // Tracing de latencia por ferramenta (Fase 4.2): mede o tempo de execucao e loga
        // com o tenant corrente (do TenantContext assinado). O latency_ms persistido fica
        // a cargo do AiCopilotService (que tem a sessao/role da mensagem).
        val start = System.currentTimeMillis()
        val tenantSlug = TenantContext.get() ?: "?"
        val result = try {
            when (name) {
                "get_dre" -> getDre(argStr(args, "period") ?: "current_month")
                "get_top_products" -> getTopProducts(argInt(args, "limit") ?: 5, argStr(args, "period") ?: "current_month")
                "get_rfv_summary" -> getRfvSummary()
                "get_recent_orders" -> getRecentOrders(argInt(args, "limit") ?: 10)
                "get_loyalty_stats" -> getLoyaltyStats()
                "get_abandoned_carts" -> getAbandonedCarts()
                "get_customers_at_risk" -> getCustomersAtRisk(argInt(args, "limit") ?: 10)
                "create_coupon" -> createCoupon(args, actorUserId, userRoles)
                "schedule_campaign" -> scheduleCampaign(args, userRoles)
                else -> json(mapOf("error" to "Ferramenta desconhecida: $name"))
            }
        } catch (e: Exception) {
            // Erro de ferramenta NUNCA derruba a conversa: devolve a mensagem ao LLM, que
            // explica ao dono. (Ex.: cupom duplicado -> BusinessException com texto util.)
            log.warn("Falha na ferramenta {}: {}", name, e.message)
            json(mapOf("error" to (e.message ?: "Falha ao executar $name")))
        }
        log.info("AI tool {} took {}ms for tenant {}", name, System.currentTimeMillis() - start, tenantSlug)
        return result
    }

    // ----------------------------- Ferramentas de consulta -----------------------------

    private fun getDre(period: String): String {
        val (start, end) = periodRange(period)
        val dre = dreService.compute(start, end)
        return json(
            mapOf(
                "periodo" to period,
                "de" to start.toString(),
                "ate" to end.toString(),
                "receitaBrutaReais" to reais(dre.grossRevenueCents),
                "receitaLiquidaReais" to reais(dre.netRevenueCents),
                "cmvReais" to reais(dre.cogsCents),
                "lucroBrutoReais" to reais(dre.grossProfitCents),
                "despesasOperacionaisReais" to reais(dre.operatingExpensesCents),
                "lucroLiquidoReais" to reais(dre.netProfitCents),
                "qtdPedidos" to dre.orderCount,
                "ticketMedioReais" to reais(dre.averageTicketCents),
                "margemBrutaPct" to dre.grossMarginPct,
                "margemLiquidaPct" to dre.netMarginPct,
            ),
        )
    }

    private fun getTopProducts(limit: Int, period: String): String {
        val (start, _) = periodRange(period)
        val from = start.atStartOfDay(zone).toInstant()
        val rows = orderItemRepository.topProductsSince(from, PageRequest.of(0, limit.coerceIn(1, 20)))
        val list = rows.map { r ->
            mapOf(
                "nome" to r[0],
                "pedidos" to (r[1] as Number).toLong(),
                "receitaReais" to reais((r[2] as Number).toLong()),
            )
        }
        return json(mapOf("periodo" to period, "produtos" to list))
    }

    private fun getRfvSummary(): String {
        val counts = rfvService.scoreAll().groupingBy { it.segment }.eachCount()
        return json(
            mapOf(
                "fieis" to (counts[RfvSegment.LOYAL] ?: 0),
                "emRisco" to (counts[RfvSegment.AT_RISK] ?: 0),
                "inativos" to (counts[RfvSegment.INACTIVE] ?: 0),
                "novos" to (counts[RfvSegment.NEW] ?: 0),
            ),
        )
    }

    private fun getRecentOrders(limit: Int): String {
        val orders = orderRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit.coerceIn(1, 50)))
        val list = orders.map { o ->
            mapOf(
                "numero" to o.orderNumber,
                "status" to o.status.name,
                "totalReais" to reais(o.totalCents),
                "canal" to o.salesChannel.name,
                "pagamento" to (o.paymentMethod?.name ?: "PENDENTE"),
                "criadoEm" to o.createdAt.toString(),
            )
        }
        return json(mapOf("pedidos" to list))
    }

    private fun getLoyaltyStats(): String = json(
        mapOf(
            "clientesComPontos" to customerRepository.countByLoyaltyPointsGreaterThan(0),
            "pontosCreditados" to loyaltyTransactionRepository.sumPointsCredited(),
            "recompensasPendentes" to loyaltyRewardRepository.countByRedeemedAtIsNull(),
            "recompensasTotais" to loyaltyRewardRepository.count(),
        ),
    )

    private fun getAbandonedCarts(): String {
        val from = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        return json(
            mapOf(
                "ativos" to cartSessionRepository.countByStatusAndCreatedAtGreaterThanEqual(CartSessionStatus.ACTIVE, from),
                "mensagensEnviadas" to cartSessionRepository.countByStatusAndCreatedAtGreaterThanEqual(CartSessionStatus.SENT, from),
                "recuperados" to cartSessionRepository.countByStatusAndCreatedAtGreaterThanEqual(CartSessionStatus.RECOVERED, from),
            ),
        )
    }

    private fun getCustomersAtRisk(limit: Int): String {
        val atRisk = rfvService.scoresBySegment(RfvSegment.AT_RISK).take(limit.coerceIn(1, 50))
        val byId = customerRepository.findAllById(atRisk.map { it.customerId }).associateBy { it.id }
        val list = atRisk.map { s ->
            mapOf(
                "nome" to (s.customerName ?: byId[s.customerId]?.name),
                "telefone" to byId[s.customerId]?.phoneNumber,
                "diasSemComprar" to s.recencyDays,
            )
        }
        return json(mapOf("clientes" to list))
    }

    // ----------------------------- Ferramentas de acao -----------------------------

    private fun createCoupon(args: Map<String, Any?>, actorUserId: UUID?, userRoles: List<String>): String {
        if (!userRoles.contains("ADMIN")) {
            return json(mapOf("error" to "Apenas um ADMIN pode criar cupons."))
        }
        val code = argStr(args, "code") ?: return json(mapOf("error" to "Informe o codigo do cupom."))
        val type = when ((argStr(args, "type") ?: "").uppercase()) {
            "FIXED" -> DiscountType.FIXED
            "PERCENT" -> DiscountType.PERCENT
            else -> return json(mapOf("error" to "Tipo invalido: use FIXED ou PERCENT."))
        }
        val value = argLong(args, "value") ?: return json(mapOf("error" to "Informe o valor do desconto."))
        val now = Instant.now()
        val coupon = couponService.create(
            CouponCreateRequest(
                code = code,
                description = argStr(args, "description"),
                discountType = type,
                discountValue = value,
                maxUses = argInt(args, "maxUses"),
                // Validade padrao: a partir de agora por 1 ano (o dono ajusta no painel).
                validFrom = now.minus(1, ChronoUnit.MINUTES),
                validUntil = now.plus(365, ChronoUnit.DAYS),
            ),
            actorId = actorUserId,
        )
        return json(mapOf("ok" to true, "couponId" to coupon.id.toString(), "code" to coupon.code))
    }

    private fun scheduleCampaign(args: Map<String, Any?>, userRoles: List<String>): String {
        if (!userRoles.contains("ADMIN")) {
            return json(mapOf("error" to "Apenas um ADMIN pode agendar campanhas."))
        }
        val name = argStr(args, "name") ?: return json(mapOf("error" to "Informe o nome da campanha."))
        val message = argStr(args, "message") ?: return json(mapOf("error" to "Informe a mensagem da campanha."))
        val segment = when ((argStr(args, "segment") ?: "ALL").uppercase()) {
            "ALL" -> CampaignSegment.ALL_OPT_IN
            "LOYAL" -> CampaignSegment.RFV_LOYAL
            "AT_RISK" -> CampaignSegment.RFV_AT_RISK
            "INACTIVE" -> CampaignSegment.RFV_INACTIVE
            else -> CampaignSegment.ALL_OPT_IN
        }
        // Cria em DRAFT (CampaignService.create nunca dispara — o envio exige start()
        // manual no painel). Retorna o numero estimado de destinatarios elegiveis.
        val campaign = campaignService.create(
            CampaignCreateRequest(name = name, messageTemplate = message, segment = segment),
        )
        return json(
            mapOf(
                "ok" to true,
                "campaignId" to campaign.id.toString(),
                "name" to campaign.name,
                "estimatedRecipients" to campaign.totalRecipients,
                "status" to campaign.status.name,
            ),
        )
    }

    // ----------------------------- Helpers -----------------------------

    /** Converte o periodo logico em [start, end] (datas inclusivas, fuso do negocio). */
    private fun periodRange(period: String): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(zone)
        return when (period.lowercase()) {
            "last_month" -> {
                val firstLast = today.withDayOfMonth(1).minusMonths(1)
                firstLast to firstLast.withDayOfMonth(firstLast.lengthOfMonth())
            }
            "last_7_days" -> today.minusDays(6) to today
            else -> today.withDayOfMonth(1) to today // current_month (default)
        }
    }

    private fun reais(cents: Long): Double = cents / 100.0

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)

    private fun argStr(args: Map<String, Any?>, key: String): String? =
        (args[key] as? String)?.trim()?.ifBlank { null }

    private fun argInt(args: Map<String, Any?>, key: String): Int? =
        (args[key] as? Number)?.toInt() ?: (args[key] as? String)?.toIntOrNull()

    private fun argLong(args: Map<String, Any?>, key: String): Long? =
        (args[key] as? Number)?.toLong() ?: (args[key] as? String)?.toLongOrNull()

    // --- construcao das definicoes JSON (formato OpenAI function tool) ---

    private fun function(name: String, description: String, props: Map<String, Any?>, required: List<String> = emptyList()): AiTool =
        AiTool(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to name,
                    "description" to description,
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to props,
                        "required" to required,
                    ),
                ),
            ),
        )

    private fun strProp(desc: String) = mapOf("type" to "string", "description" to desc)
    private fun numProp(desc: String) = mapOf("type" to "number", "description" to desc)
    private fun intProp(desc: String, min: Int, max: Int) =
        mapOf("type" to "integer", "description" to desc, "minimum" to min, "maximum" to max)
    private fun enumProp(desc: String, values: List<String>) =
        mapOf("type" to "string", "description" to desc, "enum" to values)
}

package com.menuflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.client.AiTool
import com.menuflow.model.OrderStatus
import com.menuflow.repository.tenant.CategoryRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.ProductRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Catalogo de ferramentas do BOT WhatsApp (Fase 4.3) — atendimento ao CLIENTE. E
 * DELIBERADAMENTE menor e mais restrito que o [AiToolRegistry] do copiloto do dono:
 *
 *  - SOMENTE leitura: cardapio, status do proprio pedido, horario de funcionamento.
 *    NENHUMA ferramenta de acao (sem create_coupon/schedule_campaign) e NENHUMA
 *    ferramenta de negocio do dono (sem DRE/RFV/faturamento/lista de clientes).
 *    Um cliente no WhatsApp jamais pode alcancar dados do dono ou de outros clientes.
 *
 *  - get_order_status NAO recebe telefone do LLM: o telefone e SEMPRE o do
 *    remetente verificado (o `from` do webhook). Isso impede que o cliente (ou o
 *    proprio modelo) enumere pedidos de OUTRO numero (BOLA/IDOR).
 *
 * Tudo roda no banco do tenant bound em TenantContext (db-per-tenant isola). Resultados
 * sao JSON-string consumidos pelo LLM como conteudo de mensagem 'tool'.
 */
@Service
class BotToolRegistry(
    private val categoryRepository: CategoryRepository,
    private val productRepository: ProductRepository,
    private val orderRepository: OrderRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of("America/Sao_Paulo")

    /** Definicoes enviadas ao LLM. Nenhuma ferramenta recebe telefone/parametros sensiveis. */
    fun toolDefinitions(): List<AiTool> = listOf(
        function(
            "get_menu_summary",
            "Retorna um resumo do cardapio: categorias e principais produtos com precos.",
        ),
        function(
            "get_order_status",
            "Retorna a situacao do ULTIMO pedido do cliente que esta conversando agora (status, valor e quando foi feito).",
        ),
        function(
            "get_opening_hours",
            "Retorna os horarios de funcionamento da semana e informa se o restaurante esta aberto agora.",
        ),
    )

    /**
     * Executa a ferramenta [name]. [customerPhone] e o telefone VERIFICADO do remetente
     * (vem do webhook, nunca do LLM) — usado por get_order_status para garantir que o
     * cliente so ve o proprio pedido. Roda em transacao curta de leitura no tenant.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun execute(name: String, customerPhone: String): String {
        val start = System.currentTimeMillis()
        val result = try {
            when (name) {
                "get_menu_summary" -> getMenuSummary()
                "get_order_status" -> getOrderStatus(customerPhone)
                "get_opening_hours" -> getOpeningHours()
                else -> json(mapOf("error" to "Ferramenta desconhecida: $name"))
            }
        } catch (e: Exception) {
            log.warn("Falha na ferramenta de bot {}: {}", name, e.message)
            json(mapOf("error" to (e.message ?: "Falha ao executar $name")))
        }
        log.info("Bot tool {} took {}ms", name, System.currentTimeMillis() - start)
        return result
    }

    // ----------------------------- Ferramentas -----------------------------

    /**
     * Resumo do cardapio: categorias ativas com ate 5 produtos cada. A resposta e
     * limitada a [MENU_MAX_CHARS] para nao estourar o contexto/custo do LLM.
     */
    private fun getMenuSummary(): String {
        val categories = categoryRepository.findByActiveTrue(PageRequest.of(0, 50)).content
            .sortedBy { it.displayOrder }
        val products = productRepository.findByActiveTrue(PageRequest.of(0, 500)).content
        val byCategory = products.groupBy { it.categoryId }

        val sb = StringBuilder()
        for (cat in categories) {
            val prods = byCategory[cat.id].orEmpty().take(5)
            if (prods.isEmpty()) continue
            val line = prods.joinToString(", ") { "${it.name} (R$ ${reaisBr(it.priceCents)})" }
            val entry = "${cat.name}: $line\n"
            if (sb.length + entry.length > MENU_MAX_CHARS) {
                sb.append("...")
                break
            }
            sb.append(entry)
        }
        val text = sb.toString().trim().ifBlank { "Cardapio ainda nao cadastrado." }
        return json(mapOf("cardapio" to text))
    }

    /**
     * Situacao do ULTIMO pedido do cliente. So enxerga o telefone do proprio remetente
     * (parametro confiavel, nunca do LLM). Sem pedido -> informa que nao encontrou.
     */
    private fun getOrderStatus(customerPhone: String): String {
        if (customerPhone.isBlank()) {
            return json(mapOf("encontrado" to false, "mensagem" to "Nao foi possivel identificar seu numero."))
        }
        val order = orderRepository.findTopByCustomerPhoneOrderByCreatedAtDesc(customerPhone)
            ?: return json(mapOf("encontrado" to false, "mensagem" to "Nenhum pedido encontrado para o seu numero."))

        val minutes = Duration.between(order.createdAt, Instant.now()).toMinutes().coerceAtLeast(0)
        return json(
            mapOf(
                "encontrado" to true,
                "numero" to order.orderNumber,
                "status" to statusPt(order.status),
                "valorReais" to "R$ ${reaisBr(order.totalCents)}",
                "haMinutos" to minutes,
                "resumo" to "Seu pedido de R$ ${reaisBr(order.totalCents)} esta ${statusPt(order.status)} (ha $minutes min).",
            ),
        )
    }

    /**
     * Horarios da semana + se esta aberto agora (fuso America/Sao_Paulo). Cada dia e
     * "HH:mm-HH:mm" no tenant_config, ou null (fechado).
     */
    private fun getOpeningHours(): String {
        val config = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()
        val byDay = linkedMapOf(
            "segunda" to config?.openingHoursMonday,
            "terca" to config?.openingHoursTuesday,
            "quarta" to config?.openingHoursWednesday,
            "quinta" to config?.openingHoursThursday,
            "sexta" to config?.openingHoursFriday,
            "sabado" to config?.openingHoursSaturday,
            "domingo" to config?.openingHoursSunday,
        )
        val now = ZonedDateTime.now(zone)
        val todayName = DAY_NAMES[now.dayOfWeek.value - 1]
        val todaySpec = byDay[todayName]
        val openNow = isOpenAt(todaySpec, now.toLocalTime())
        val horarios = byDay.entries.associate { (dia, spec) -> dia to (spec ?: "fechado") }
        return json(
            mapOf(
                "horarios" to horarios,
                "hoje" to todayName,
                "horarioHoje" to (todaySpec ?: "fechado"),
                "abertoAgora" to openNow,
            ),
        )
    }

    // ----------------------------- Helpers -----------------------------

    /** Centavos -> "45,00" (formato BR; sem float). */
    private fun reaisBr(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign${abs / 100},${(abs % 100).toString().padStart(2, '0')}"
    }

    private fun statusPt(status: OrderStatus): String = when (status) {
        OrderStatus.PENDING -> "RECEBIDO"
        OrderStatus.PREPARING -> "em PREPARO"
        OrderStatus.READY -> "PRONTO"
        OrderStatus.DELIVERED -> "ENTREGUE"
        OrderStatus.CANCELLED -> "CANCELADO"
    }

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)

    private fun function(name: String, description: String): AiTool =
        AiTool(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to name,
                    "description" to description,
                    "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>()),
                ),
            ),
        )

    companion object {
        private const val MENU_MAX_CHARS = 800
        private val DAY_NAMES = listOf("segunda", "terca", "quarta", "quinta", "sexta", "sabado", "domingo")
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * Parseia "HH:mm-HH:mm" em (abertura, fechamento). Invalido/null -> null.
         * Funcao PURA (testavel sem DB).
         */
        fun parseHours(spec: String?): Pair<LocalTime, LocalTime>? {
            if (spec.isNullOrBlank()) return null
            val parts = spec.trim().split("-")
            if (parts.size != 2) return null
            return try {
                LocalTime.parse(parts[0].trim(), TIME_FMT) to LocalTime.parse(parts[1].trim(), TIME_FMT)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Esta aberto em [time] dado o spec do dia? Trata virada de meia-noite
         * (ex.: "18:00-02:00" = fecha 2h do dia seguinte). Funcao PURA.
         */
        fun isOpenAt(spec: String?, time: LocalTime): Boolean {
            val (open, close) = parseHours(spec) ?: return false
            return if (close > open) {
                // Mesmo dia: [open, close).
                !time.isBefore(open) && time.isBefore(close)
            } else {
                // Vira a meia-noite: aberto se >= open OU < close.
                !time.isBefore(open) || time.isBefore(close)
            }
        }
    }
}

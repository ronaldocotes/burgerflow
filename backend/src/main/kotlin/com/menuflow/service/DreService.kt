package com.menuflow.service

import com.menuflow.dto.DreResponse
import com.menuflow.exception.BusinessException
import com.menuflow.model.SalesChannel
import com.menuflow.repository.tenant.LoyaltyRewardRepository
import com.menuflow.repository.tenant.LoyaltyTransactionRepository
import com.menuflow.repository.tenant.OperatingExpenseRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.repository.tenant.TenantConfigRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * DRE Automático (Fase 3.1): demonstrativo de resultado de um período, calculado
 * a partir dos snapshots de venda (cogs/marketplace/cartão gravados no pedido no
 * momento da venda) + alíquotas do tenant_config + despesas operacionais.
 *
 * Tudo no banco do TENANT (escopo garantido pelo datasource roteado). Dinheiro
 * SEMPRE em centavos. O período é dado em datas (LocalDate) e convertido em
 * limites instantâneos de dia no fuso do negócio (America/Sao_Paulo), mesmo
 * padrão do KDS/caixa/acerto — evita timezone dentro do SQL.
 */
@Service
class DreService(
    private val orderRepository: OrderRepository,
    private val operatingExpenseRepository: OperatingExpenseRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val loyaltyTransactionRepository: LoyaltyTransactionRepository,
    private val loyaltyRewardRepository: LoyaltyRewardRepository,
) {
    private val zone = ZoneId.of("America/Sao_Paulo")

    /**
     * Calcula o DRE para [start, end] (ambos inclusivos). Período sem pedidos
     * retorna todos os valores em zero (sem NPE) — as somas usam COALESCE 0.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun compute(start: LocalDate, end: LocalDate): DreResponse {
        if (end.isBefore(start)) throw BusinessException("Período inválido: fim antes do início")

        // Receita só conta pedidos DELIVERED concluídos no período (completedAt).
        val from: Instant = start.atStartOfDay(zone).toInstant()
        val to: Instant = end.plusDays(1).atStartOfDay(zone).toInstant()

        val agg = orderRepository.dreAggregate(from, to).first()
        val gross = num(agg[0])
        val marketplaceFees = num(agg[1])
        val cardFees = num(agg[2])
        val cogs = num(agg[3])
        val orderCount = num(agg[4])
        // Descontos adicionados na Fase 3.2 para separar cupons de descontos manuais.
        val couponDiscount = num(agg[5])
        val totalDiscount = num(agg[6])
        val manualDiscount = totalDiscount - couponDiscount

        val taxPct = tenantConfigRepository.findFirstByOrderByCreatedAtAsc()?.taxPct ?: BigDecimal.ZERO
        val tax = pctOf(gross, taxPct)

        val netRevenue = gross - marketplaceFees - cardFees - tax
        val grossProfit = netRevenue - cogs
        val operatingExpenses = operatingExpenseRepository.sumInPeriod(start, end)
        val netProfit = grossProfit - operatingExpenses

        val averageTicket =
            if (orderCount == 0L) 0L
            else BigDecimal.valueOf(gross)
                .divide(BigDecimal.valueOf(orderCount), 0, RoundingMode.HALF_UP).toLong()

        // Métricas de fidelidade no mesmo período (informativas — não alteram margem).
        val loyaltyPointsIssued = loyaltyTransactionRepository.sumPointsIssuedInPeriod(from, to)
        val loyaltyRewardsRedeemed = loyaltyRewardRepository.countRedeemedInPeriod(from, to)

        return DreResponse(
            periodStart = start,
            periodEnd = end,
            grossRevenueCents = gross,
            marketplaceFeesCents = marketplaceFees,
            cardFeesCents = cardFees,
            taxCents = tax,
            netRevenueCents = netRevenue,
            cogsCents = cogs,
            grossProfitCents = grossProfit,
            operatingExpensesCents = operatingExpenses,
            netProfitCents = netProfit,
            couponDiscountCents = couponDiscount,
            manualDiscountCents = manualDiscount,
            totalDiscountCents = totalDiscount,
            orderCount = orderCount,
            averageTicketCents = averageTicket,
            grossMarginPct = marginPct(grossProfit, gross),
            netMarginPct = marginPct(netProfit, gross),
            ordersByChannel = channelBreakdown(from, to),
            ordersByPaymentMethod = paymentBreakdown(from, to),
            loyaltyPointsIssued = loyaltyPointsIssued,
            loyaltyRewardsRedeemed = loyaltyRewardsRedeemed,
        )
    }

    /** Atalho de período para o dashboard: today | week | month (até hoje). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun summary(period: String): DreResponse {
        val today = LocalDate.now(zone)
        val start = when (period.lowercase()) {
            "today" -> today
            "week" -> today.with(DayOfWeek.MONDAY) // semana ISO corrente (seg..hoje)
            "month" -> today.withDayOfMonth(1)
            else -> throw BusinessException("Período inválido: use today, week ou month")
        }
        return compute(start, today)
    }

    // --- helpers ---

    /** Quantidade de pedidos por canal de venda (DELIVERED no período). */
    private fun channelBreakdown(from: Instant, to: Instant): Map<String, Long> =
        orderRepository.countByChannel(from, to).associate { row ->
            (row[0] as SalesChannel).name to num(row[1])
        }

    /**
     * Quantidade de pedidos por forma de pagamento (DELIVERED no período). Pedido
     * sem forma registrada (paymentMethod null — possível em entregas marcadas
     * concluídas sem pagamento no sistema) é agrupado como "UNKNOWN".
     */
    private fun paymentBreakdown(from: Instant, to: Instant): Map<String, Long> =
        orderRepository.countByPaymentMethod(from, to).associate { row ->
            (row[0] as? Enum<*>)?.name.orEmpty().ifEmpty { "UNKNOWN" } to num(row[1])
        }

    /** Converte um agregado JPQL (Number) em Long de forma segura. */
    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: 0L

    /**
     * Aplica uma alíquota (%) sobre um valor em centavos, HALF-UP, em BigDecimal
     * (sem float) para o resultado em centavos fechar de forma determinística.
     */
    private fun pctOf(cents: Long, pct: BigDecimal): Long =
        BigDecimal.valueOf(cents).multiply(pct)
            .divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()

    /** Margem percentual (part/base*100) com 2 casas; base zero -> 0,0. */
    private fun marginPct(part: Long, base: Long): Double =
        if (base == 0L) 0.0
        else BigDecimal.valueOf(part).multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(base), 2, RoundingMode.HALF_UP).toDouble()
}

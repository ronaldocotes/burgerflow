package com.menuflow.service

import com.menuflow.model.RfvScore
import com.menuflow.model.RfvSegment
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Classificacao RFV (Recencia, Frequencia, Valor) dos clientes do tenant (Fase 3.4).
 * Calculada sobre os pedidos NAO cancelados do banco do TENANT:
 *  - Recencia: dias desde o ultimo pedido (sobre TODA a historia).
 *  - Frequencia: numero de pedidos nos ultimos 90 dias.
 *  - Valor: ticket medio (centavos) nos ultimos 90 dias.
 *
 * A classificacao em [RfvSegment] usa limiares fixos (ver [classify]); e a base de
 * segmentacao das campanhas (CampaignService.buildRecipients).
 */
@Service
class RfvService(
    private val orderRepository: OrderRepository,
    private val customerRepository: CustomerRepository,
) {

    /** Score RFV de todos os clientes que ja fizeram ao menos um pedido. */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun scoreAll(): List<RfvScore> {
        val now = Instant.now()
        val windowStart = now.minus(WINDOW_DAYS, ChronoUnit.DAYS)
        val rows = orderRepository.rfvAggregate(windowStart)

        // Nomes para a resposta: 1 query (findAllById), evita N+1.
        val ids = rows.mapNotNull { it[0] as? UUID }
        val names = customerRepository.findAllById(ids).associate { it.id to it.name }

        return rows.mapNotNull { row ->
            val customerId = row[0] as? UUID ?: return@mapNotNull null
            val lastOrder = toInstant(row[1]) ?: return@mapNotNull null
            val freq90 = (row[2] as Number).toInt()
            val sum90 = (row[3] as Number).toLong()
            val lifetime = (row[4] as Number).toInt()

            val recencyDays = ChronoUnit.DAYS.between(lastOrder, now).toInt().coerceAtLeast(0)
            val monetaryValue = if (freq90 > 0) sum90 / freq90 else 0L
            RfvScore(
                customerId = customerId,
                customerName = names[customerId],
                recencyDays = recencyDays,
                frequency = freq90,
                monetaryValue = monetaryValue,
                segment = classify(recencyDays, freq90, lifetime),
            )
        }
    }

    /** Score por segmento (para o endpoint GET /rfv?segment=). */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun scoresBySegment(segment: RfvSegment?): List<RfvScore> {
        val all = scoreAll()
        return if (segment == null) all else all.filter { it.segment == segment }
    }

    /** Mapa customerId -> score, usado pelo CampaignService.buildRecipients. */
    fun scoreMap(): Map<UUID, RfvScore> = scoreAll().associateBy { it.customerId }

    /** Converte o resultado de MAX(createdAt) (pode vir Instant/Timestamp/OffsetDateTime). */
    private fun toInstant(v: Any?): Instant? = when (v) {
        is Instant -> v
        is java.sql.Timestamp -> v.toInstant()
        is OffsetDateTime -> v.toInstant()
        else -> null
    }

    companion object {
        /** Janela (dias) para frequencia e valor. */
        const val WINDOW_DAYS = 90L

        /**
         * Classifica o cliente em [RfvSegment] de forma TOTAL e deterministica:
         *  1. lifetime <= 1            -> NEW       (apenas 1 pedido na vida)
         *  2. recencyDays > 45         -> INACTIVE  (sumido)
         *  3. recencyDays < 14 e freq90 >= 3 -> LOYAL (recente e frequente)
         *  4. caso contrario           -> AT_RISK   (tem historia, nem sumiu nem e fiel)
         *
         * AT_RISK e o catch-all do cliente ativo-mas-nao-fiel: o objetivo de marketing
         * e justamente reengaja-lo. Pura (sem I/O) para teste unitario direto.
         */
        fun classify(recencyDays: Int, freq90: Int, lifetimeOrders: Int): RfvSegment = when {
            lifetimeOrders <= 1 -> RfvSegment.NEW
            recencyDays > 45 -> RfvSegment.INACTIVE
            recencyDays < 14 && freq90 >= 3 -> RfvSegment.LOYAL
            else -> RfvSegment.AT_RISK
        }
    }
}

package com.menuflow.service

import com.menuflow.dto.RfvSummaryResponse
import com.menuflow.model.RfvScore
import com.menuflow.model.RfvSegment
import com.menuflow.repository.tenant.CustomerRepository
import com.menuflow.repository.tenant.OrderRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.Cacheable
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Classificacao RFV (Recencia, Frequencia, Valor) dos clientes do tenant (Fase 3.4).
 * Calculada sobre os pedidos NAO cancelados do banco do TENANT.
 *
 * Cache: scoreAll e anotado com @Cacheable("rfv-scores"). A chave inclui o slug
 * do tenant (TenantContext.get()) para isolamento cross-tenant. TTL 10 min
 * (configurado em CacheConfig). A chamada via self (proxy injetado via @Lazy)
 * garante que o Spring intercepte a anotacao (auto-invocacao nao passa pelo proxy).
 */
@Service
class RfvService(
    private val orderRepository: OrderRepository,
    private val customerRepository: CustomerRepository,
) {

    /**
     * Referencia ao proprio proxy Spring para garantir que @Cacheable seja interceptado
     * em chamadas internas (scoresBySegment/scoreMap/summary -> scoreAll).
     * @Lazy evita dependencia circular no contexto.
     */
    @Lazy
    @Autowired
    private lateinit var self: RfvService

    /**
     * Score RFV de todos os clientes que ja fizeram ao menos um pedido.
     * Resultado cacheado por tenant (chave = slug do tenant) com TTL 10 min.
     */
    @Cacheable("rfv-scores", key = "T(com.menuflow.tenant.TenantContext).INSTANCE.get()")
    @Transactional("tenantTransactionManager", readOnly = true)
    fun scoreAll(): List<RfvScore> {
        val now = Instant.now()
        val windowStart = now.minus(WINDOW_DAYS, ChronoUnit.DAYS)
        val rows = orderRepository.rfvAggregate(windowStart)

        if (rows.isEmpty()) return emptyList()

        // Nomes e telefones: 1 query batch, sem N+1.
        val ids = rows.mapNotNull { it[0] as? UUID }
        val customers = customerRepository.findAllById(ids).associateBy { it.id }

        return rows.mapNotNull { row ->
            val customerId = row[0] as? UUID ?: return@mapNotNull null
            val lastOrder = toInstant(row[1]) ?: return@mapNotNull null
            val freq90 = (row[2] as Number).toInt()
            val sum90 = (row[3] as Number).toLong()
            val lifetime = (row[4] as Number).toInt()

            val recencyDays = ChronoUnit.DAYS.between(lastOrder, now).toInt().coerceAtLeast(0)
            val monetaryValue = if (freq90 > 0) sum90 / freq90 else 0L
            val customer = customers[customerId]
            RfvScore(
                customerId = customerId,
                customerName = customer?.name,
                phoneNumber = customer?.phoneNumber,
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
        val all = self.scoreAll()
        return if (segment == null) all else all.filter { it.segment == segment }
    }

    /** Mapa customerId -> score, usado pelo CampaignService.buildRecipients. */
    fun scoreMap(): Map<UUID, RfvScore> = self.scoreAll().associateBy { it.customerId }

    /**
     * Sumario de contagem por segmento para GET /rfv/summary.
     * Reutiliza o resultado cacheado de scoreAll(); sem query adicional.
     */
    fun summary(): RfvSummaryResponse {
        val all = self.scoreAll()
        val counts = all.groupingBy { it.segment }.eachCount()
        return RfvSummaryResponse(
            loyal        = (counts[RfvSegment.LOYAL]    ?: 0).toLong(),
            atRisk       = (counts[RfvSegment.AT_RISK]  ?: 0).toLong(),
            inactive     = (counts[RfvSegment.INACTIVE] ?: 0).toLong(),
            newCustomers = (counts[RfvSegment.NEW]      ?: 0).toLong(),
            total        = all.size.toLong(),
        )
    }

    /** Converte o resultado de MAX(createdAt): pode chegar como Instant/Timestamp/OffsetDateTime. */
    private fun toInstant(v: Any?): Instant? = when (v) {
        is Instant            -> v
        is java.sql.Timestamp -> v.toInstant()
        is OffsetDateTime     -> v.toInstant()
        else                  -> null
    }

    companion object {
        const val WINDOW_DAYS = 90L

        /**
         * Classifica o cliente em RfvSegment de forma TOTAL e deterministica:
         *  1. lifetime <= 1                   -> NEW
         *  2. recencyDays > 45                -> INACTIVE
         *  3. recencyDays < 14 && freq90 >= 3 -> LOYAL
         *  4. caso contrario                  -> AT_RISK
         *
         * Funcao pura (sem I/O) — testavel diretamente.
         */
        fun classify(recencyDays: Int, freq90: Int, lifetimeOrders: Int): RfvSegment = when {
            lifetimeOrders <= 1              -> RfvSegment.NEW
            recencyDays > 45                -> RfvSegment.INACTIVE
            recencyDays < 14 && freq90 >= 3 -> RfvSegment.LOYAL
            else                            -> RfvSegment.AT_RISK
        }
    }
}

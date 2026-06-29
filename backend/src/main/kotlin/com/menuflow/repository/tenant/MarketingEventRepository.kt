package com.menuflow.repository.tenant

import com.menuflow.model.MarketingEvent
import com.menuflow.model.MarketingEventType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface MarketingEventRepository : JpaRepository<MarketingEvent, UUID> {

    /** Idempotencia da conversao: ja existe um CONVERSION para este pedido? */
    fun existsByOrderIdAndEventType(orderId: UUID, eventType: MarketingEventType): Boolean

    /**
     * Resumo ROAS por link rastreavel ativo na janela [from, to]. Por link: cliques,
     * conversoes e receita total (centavos). LEFT JOIN para que um link sem nenhum
     * evento na janela ainda apareca com zeros. Os COUNT/SUM condicionais separam CLICK
     * de CONVERSION numa unica varredura.
     *
     * Retorno: List<Array<Any>> (uma linha por link, ha GROUP BY). Cada linha:
     * [0]=id(UUID) [1]=name(String) [2]=source(String) [3]=slug(String)
     * [4]=clicks(Long) [5]=conversions(Long) [6]=revenueCents(Number).
     */
    @Query(
        """
        SELECT tl.id, tl.name, tl.source, tl.slug,
               COUNT(CASE WHEN me.eventType = com.menuflow.model.MarketingEventType.CLICK THEN 1 END),
               COUNT(CASE WHEN me.eventType = com.menuflow.model.MarketingEventType.CONVERSION THEN 1 END),
               COALESCE(SUM(CASE WHEN me.eventType = com.menuflow.model.MarketingEventType.CONVERSION
                                 THEN me.revenueCents ELSE 0 END), 0)
        FROM TrackingLink tl
        LEFT JOIN MarketingEvent me
               ON me.trackingLink = tl AND me.occurredAt BETWEEN :from AND :to
        WHERE tl.active = true
        GROUP BY tl.id, tl.name, tl.source, tl.slug
        ORDER BY tl.name ASC
        """,
    )
    fun summaryBetween(@Param("from") from: Instant, @Param("to") to: Instant): List<Array<Any>>
}

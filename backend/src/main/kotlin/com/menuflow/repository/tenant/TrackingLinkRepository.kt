package com.menuflow.repository.tenant

import com.menuflow.model.TrackingLink
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TrackingLinkRepository : JpaRepository<TrackingLink, UUID> {

    /** Lookup pelo slug curto da URL compartilhavel. */
    fun findBySlug(slug: String): TrackingLink?

    fun existsBySlug(slug: String): Boolean

    /**
     * Incremento ATOMICO do contador de cliques (UPDATE ... SET click_count + 1),
     * evitando o anti-padrao ler-no-app/somar-um/salvar (que perderia cliques
     * concorrentes). flushAutomatically=true forca o flush das alteracoes pendentes
     * (ex.: o INSERT do marketing_event recem-salvo) ANTES desta query bulk. NAO usar
     * clearAutomatically: ele descartaria o INSERT pendente do evento de clique.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE TrackingLink t SET t.clickCount = t.clickCount + 1 WHERE t.id = :id")
    fun incrementClickCount(@Param("id") id: UUID): Int
}

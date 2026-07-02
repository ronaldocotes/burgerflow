package com.menuflow.repository.tenant

import com.menuflow.model.DeliveryOffer
import com.menuflow.model.DeliveryOfferStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface DeliveryOfferRepository : JpaRepository<DeliveryOffer, UUID> {

    /** Ofertas de um pedido num dado status (ex.: a OFFERED viva por pedido). */
    fun findByOrderIdAndStatus(orderId: UUID, status: DeliveryOfferStatus): List<DeliveryOffer>

    /** Ofertas de um entregador num dado status (fila do app do motoboy). */
    fun findByDriverIdAndStatus(driverId: UUID, status: DeliveryOfferStatus): List<DeliveryOffer>

    /**
     * Ofertas OFFERED do AUTO-ASSIGN (Fase 6.1) cujo prazo ja passou — o
     * DeliveryOfferExpiryJob as fecha. Exclui as ofertas de GRUPO (group_jid != null),
     * que sao geridas pelo DispatchScheduler (expira E reoferta) — assim os dois
     * motores de despacho nunca disputam a mesma linha.
     */
    @Query(
        """
        SELECT o FROM DeliveryOffer o
        WHERE o.status = com.menuflow.model.DeliveryOfferStatus.OFFERED
          AND o.expiresAt < :now
          AND o.groupJid IS NULL
        """,
    )
    fun findExpiredOffers(@Param("now") now: Instant): List<DeliveryOffer>

    /**
     * Ofertas de GRUPO (group_jid != null) OFFERED cujo prazo ja passou — o
     * DispatchScheduler as expira e reoferta (attempt+1) ate dispatchMaxAttempts.
     */
    @Query(
        """
        SELECT o FROM DeliveryOffer o
        WHERE o.status = com.menuflow.model.DeliveryOfferStatus.OFFERED
          AND o.expiresAt < :now
          AND o.groupJid IS NOT NULL
        """,
    )
    fun findExpiredGroupOffers(@Param("now") now: Instant): List<DeliveryOffer>

    /**
     * Aceite ATOMICO por CAS (compare-and-set). Fecha a oferta para o motoboy vencedor
     * SOMENTE se ela ainda esta OFFERED e nao expirou. Retorna o numero de linhas
     * afetadas: 1 = este motoboy venceu; 0 = a corrida ja estava fechada (outro venceu
     * ou expirou). O banco serializa UPDATEs concorrentes na mesma linha -> exatamente
     * um vencedor, sem lock otimista nem retry.
     *
     * flushAutomatically garante que qualquer escrita pendente da transacao (ex.: o
     * motoboy PROVISIONAL recem-criado) va ao banco antes do UPDATE; clearAutomatically
     * limpa o contexto para evitar entidade DeliveryOffer stale apos o UPDATE em massa.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE DeliveryOffer o
        SET o.status = com.menuflow.model.DeliveryOfferStatus.ACCEPTED,
            o.acceptedByDriverId = :driverId,
            o.acceptedAt = :now,
            o.respondedAt = :now
        WHERE o.id = :offerId
          AND o.status = com.menuflow.model.DeliveryOfferStatus.OFFERED
          AND o.expiresAt > :now
        """,
    )
    fun acceptOfferAtomic(
        @Param("offerId") offerId: UUID,
        @Param("driverId") driverId: UUID,
        @Param("now") now: Instant,
    ): Int
}

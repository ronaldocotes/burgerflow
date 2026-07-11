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

    /**
     * Oferta de grupo pelo codigo de aceite (o motoboy digita "ACEITO <codigo>" no grupo).
     * O accept_code e unico entre as ofertas OFFERED (indice parcial na V40), entao com
     * status=OFFERED retorna no maximo uma. Chave do aceite no despacho por grupo (B2).
     */
    fun findByAcceptCodeAndStatus(acceptCode: String, status: DeliveryOfferStatus): List<DeliveryOffer>

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

    /**
     * Repasse do FREELANCER no acerto por periodo (issue #3). Soma os payout_cents das
     * ofertas ACEITAS pelo entregador cujo PEDIDO ficou DELIVERED no periodo. Retorna
     * UMA linha com tres colunas, nesta ordem:
     *   [0] payoutTotal   = COALESCE(SUM(payout_cents), 0)  — SUM ignora NULL (D-C: NULL=0);
     *   [1] offersCount   = COUNT das corridas contadas (pedido DELIVERED — D-A);
     *   [2] withoutPayout = quantas dessas corridas estao sem payout definido (aviso ao front).
     *
     * Casa o motoboy por COALESCE(accepted_by_driver_id, driver_id): ofertas de GRUPO
     * (B1/B2) tem o vencedor em accepted_by_driver_id; ofertas legadas (auto-assign)
     * tem driver_id preenchido. Casa o pedido por off.orderId = o.id e status DELIVERED
     * (pedido cancelado NAO conta, mesmo com a oferta aceita — D-A). Limites [from, to)
     * vem do servico (dia em America/Sao_Paulo).
     */
    @Query(
        """
        SELECT COALESCE(SUM(off.payoutCents), 0),
               COUNT(off),
               COALESCE(SUM(CASE WHEN off.payoutCents IS NULL THEN 1 ELSE 0 END), 0)
        FROM DeliveryOffer off, Order o
        WHERE o.id = off.orderId
          AND COALESCE(off.acceptedByDriverId, off.driverId) = :driverId
          AND off.status = com.menuflow.model.DeliveryOfferStatus.ACCEPTED
          AND o.status = com.menuflow.model.OrderStatus.DELIVERED
          AND o.completedAt >= :from
          AND o.completedAt < :to
        """,
    )
    fun sumFreelancerPayoutByDriverAndPeriod(
        @Param("driverId") driverId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Array<Any>>
}

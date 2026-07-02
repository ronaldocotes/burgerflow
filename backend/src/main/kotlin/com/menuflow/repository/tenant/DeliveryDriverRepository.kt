package com.menuflow.repository.tenant

import com.menuflow.model.DeliveryDriver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DeliveryDriverRepository : JpaRepository<DeliveryDriver, UUID> {
    fun findByActiveTrueOrderByNameAsc(): List<DeliveryDriver>

    fun findAllByOrderByNameAsc(): List<DeliveryDriver>

    /** Entregador ligado a um User do banco de controle (elo 1:1). App do motoboy. */
    fun findByUserId(userId: UUID): DeliveryDriver?

    /** Entregador pelo telefone (digitos), usado para resolver o JID do WhatsApp no despacho. */
    fun findByPhone(phone: String): DeliveryDriver?

    /**
     * Entregadores disponiveis para o auto-assign: ativos, EM TURNO, com localizacao
     * conhecida e SEM oferta OFFERED viva (ociosos). A ausencia de oferta viva evita
     * ofertar dois pedidos ao mesmo motoboy ao mesmo tempo.
     */
    @Query(
        """
        SELECT d FROM DeliveryDriver d
        WHERE d.active = true
          AND d.activeShift = true
          AND d.lastLat IS NOT NULL
          AND d.lastLng IS NOT NULL
          AND NOT EXISTS (
            SELECT o FROM DeliveryOffer o
            WHERE o.driverId = d.id
              AND o.status = com.menuflow.model.DeliveryOfferStatus.OFFERED
          )
        """,
    )
    fun findAvailableWithLocation(): List<DeliveryDriver>
}

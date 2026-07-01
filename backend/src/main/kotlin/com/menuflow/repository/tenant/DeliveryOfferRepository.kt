package com.menuflow.repository.tenant

import com.menuflow.model.DeliveryOffer
import com.menuflow.model.DeliveryOfferStatus
import org.springframework.data.jpa.repository.JpaRepository
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

    /** Ofertas OFFERED cujo prazo (expires_at) ja passou — o job de expiracao as fecha. */
    @Query(
        """
        SELECT o FROM DeliveryOffer o
        WHERE o.status = com.menuflow.model.DeliveryOfferStatus.OFFERED
          AND o.expiresAt < :now
        """,
    )
    fun findExpiredOffers(@Param("now") now: Instant): List<DeliveryOffer>
}

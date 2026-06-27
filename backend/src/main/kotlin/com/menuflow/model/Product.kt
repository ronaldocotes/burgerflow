package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Product lives in the TENANT database. No tenantId discriminator is needed:
 * isolation is physical (one DB per tenant). Money is stored in CENTAVOS (Long),
 * never float/BigDecimal-as-currency — domain rule (conhecimento Seç.10).
 */
@Entity
@Table(name = "products")
data class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "category_id", nullable = false)
    var categoryId: UUID,

    @Column(nullable = false, unique = true)
    var sku: String,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var description: String = "",

    /** Sale price in centavos. */
    @Column(name = "price_cents", nullable = false)
    var priceCents: Long,

    /** Cost price in centavos (optional). */
    @Column(name = "cost_price_cents")
    var costPriceCents: Long? = null,

    @Column(name = "image_url")
    var imageUrl: String? = null,

    /** Soft-delete flag. */
    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "is_available", nullable = false)
    var isAvailable: Boolean = true,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "preparation_time_minutes", nullable = false)
    var preparationTimeMinutes: Int = 10,

    @Column(name = "is_featured", nullable = false)
    var isFeatured: Boolean = false,

    /** Preço promocional (centavos), opcional. Ativo dentro da janela [promoStartsAt, promoEndsAt]. */
    @Column(name = "promo_price_cents")
    var promoPriceCents: Long? = null,

    @Column(name = "promo_starts_at")
    var promoStartsAt: Instant? = null,

    @Column(name = "promo_ends_at")
    var promoEndsAt: Instant? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    /**
     * Promo ATIVA = existe uma janela definida (ao menos promoStartsAt ou promoEndsAt)
     * e "agora" está dentro dela (limite nulo = sem limite daquele lado). A janela é o
     * que ativa a promoção; o preço promocional (do produto OU do tamanho de pizza)
     * só é aplicado quando a promo está ativa. "Sempre ativa" = promoStartsAt no passado
     * e promoEndsAt nulo.
     */
    fun isOnPromo(now: Instant = Instant.now()): Boolean {
        val hasWindow = promoStartsAt != null || promoEndsAt != null
        return hasWindow &&
            (promoStartsAt == null || !now.isBefore(promoStartsAt)) &&
            (promoEndsAt == null || now.isBefore(promoEndsAt))
    }

    /** Preço efetivo (centavos): promocional se a promo estiver ativa E houver promoPriceCents. */
    fun effectivePriceCents(now: Instant = Instant.now()): Long =
        if (isOnPromo(now) && promoPriceCents != null) promoPriceCents!! else priceCents
}

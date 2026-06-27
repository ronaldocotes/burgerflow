package com.menuflow.model

import jakarta.persistence.*
import java.util.UUID

/**
 * Preço da borda (CrustType) por produto. O item de pizza soma este preço ao
 * preço unitário. Borda sem registro para o produto = preço 0 (ver OrderService).
 */
@Entity
@Table(
    name = "product_crust_prices",
    uniqueConstraints = [UniqueConstraint(columnNames = ["product_id", "crust_type"])],
)
data class ProductCrustPrice(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @Column(name = "product_id", nullable = false) var productId: UUID,
    @Column(name = "crust_type", nullable = false) @Enumerated(EnumType.STRING) var crustType: CrustType,
    @Column(name = "price_cents", nullable = false) var priceCents: Long = 0,
)

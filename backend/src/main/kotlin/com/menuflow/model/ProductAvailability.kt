package com.menuflow.model

import jakarta.persistence.*
import java.util.UUID

/** Canais de venda onde um produto pode ser oferecido. */
enum class SalesChannel {
    COUNTER, // balcão / PDV presencial
    DINE_IN, // consumo no local / mesa
    DELIVERY, // entrega
    ONLINE, // cardápio digital público (link/QR) e autoatendimento
}

/** Canal em que o produto está disponível. Sem registros = disponível em todos. */
@Entity
@Table(name = "product_channels")
data class ProductChannel(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @Column(name = "product_id", nullable = false) var productId: UUID,
    @Column(nullable = false) @Enumerated(EnumType.STRING) var channel: SalesChannel,
)

/**
 * Janela de horário em que o produto fica disponível. day_of_week 1=segunda..7=domingo;
 * minutos desde 00:00. Sem janelas = disponível em qualquer horário.
 */
@Entity
@Table(name = "product_availability_windows")
data class ProductAvailabilityWindow(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @Column(name = "product_id", nullable = false) var productId: UUID,
    @Column(name = "day_of_week", nullable = false) var dayOfWeek: Int,
    @Column(name = "start_minute", nullable = false) var startMinute: Int,
    @Column(name = "end_minute", nullable = false) var endMinute: Int,
)

package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * A delivery courier (Sprint 2). Lives in the TENANT database, so it is already
 * scoped to the hamburgueria by construction (one DB per tenant). [tenantId] is
 * stored redundantly for traceability/auditing only; it is NOT relied upon for
 * isolation (the physical database boundary provides that).
 */
@Entity
@Table(name = "delivery_drivers")
data class DeliveryDriver(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "name", nullable = false)
    var name: String,

    @Column(name = "phone", nullable = false)
    var phone: String,

    @Column(name = "license_plate")
    var licensePlate: String? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    /** Tenant UUID (from the signed JWT) recorded for audit; not an isolation key. */
    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    // --- Fase 6.1: elo de autenticacao, turno e ultima localizacao ---
    /** User (banco de controle, papel DRIVER) ligado a este entregador. Elo 1:1. */
    @Column(name = "user_id")
    var userId: UUID? = null,

    /** Turno ativo: motoboy online e disponivel para receber ofertas de entrega. */
    @Column(name = "active_shift", nullable = false)
    var activeShift: Boolean = false,

    /** Ultima latitude reportada pelo app (mapa ao vivo e auto-assign). */
    @Column(name = "last_lat")
    var lastLat: Double? = null,

    /** Ultima longitude reportada pelo app. */
    @Column(name = "last_lng")
    var lastLng: Double? = null,

    /** Momento da ultima posicao reportada. */
    @Column(name = "last_location_at")
    var lastLocationAt: Instant? = null,

    /** Nivel de bateria (%) reportado pelo app; telemetria. */
    @Column(name = "battery_pct")
    var batteryPct: Int? = null,

    // --- Fase B2: motoboy provisional (freelancer que entrou pelo grupo) + funil ---
    /** FROTA (contratado) ou FREELANCER (entrou pelo grupo). Default FROTA. */
    @Column(name = "driver_type", nullable = false, length = 12)
    var driverType: String = "FROTA",

    /** Criado automaticamente pelo aceite no grupo, ainda sem cadastro completo. */
    @Column(name = "provisional", nullable = false)
    var provisional: Boolean = false,

    /** Momento da 1a entrega CONCLUIDA (carimbado no DELIVERED); gatilho do recrutamento. */
    @Column(name = "first_delivery_at")
    var firstDeliveryAt: Instant? = null,

    /** Token do link publico de auto-cadastro do freelancer (unico quando presente). */
    @Column(name = "signup_token")
    var signupToken: UUID? = null,

    /** Momento em que o convite de cadastro foi enviado (evita reenvio). */
    @Column(name = "recruitment_sent_at")
    var recruitmentSentAt: Instant? = null,

    // --- Fase C1: dados legais/cadastrais preenchidos no auto-cadastro publico ---
    /** CPF no formato "000.000.000-00". LGPD: coletado para repasse, nunca exposto em publico. */
    @Column(name = "cpf", length = 14)
    var cpf: String? = null,

    /** Categoria da CNH: "A", "AB", "B". */
    @Column(name = "cnh_category", length = 5)
    var cnhCategory: String? = null,

    /** Tipo de veiculo: "MOTO", "CARRO", "VAN". */
    @Column(name = "vehicle_type", length = 20)
    var vehicleType: String? = null,

    /** Chave PIX para repasse do motoboy. */
    @Column(name = "pix_key", length = 100)
    var pixKey: String? = null,

    /** Tipo da chave PIX: "CPF", "PHONE", "EMAIL", "RANDOM". */
    @Column(name = "pix_key_type", length = 20)
    var pixKeyType: String? = null,

    /** Endereco residencial. LGPD: so coleta, nunca exposto em endpoint publico. */
    @Column(name = "full_address", length = 255)
    var fullAddress: String? = null,

    /** Momento em que o motoboy concluiu o auto-cadastro (deixa de ser provisional). */
    @Column(name = "registration_completed_at")
    var registrationCompletedAt: Instant? = null,

    /** Momento do aceite dos termos (LGPD/consentimento). */
    @Column(name = "terms_accepted_at")
    var termsAcceptedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

package com.menuflow.model.control

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Vinculo de um TENANT (company) a uma plataforma Open Delivery (99Food / Rappi),
 * no banco de CONTROLE.
 *
 * Diferente do iFood (OAuth hibrido com refresh token), o Open Delivery usa
 * OAuth2 client_credentials: guarda client_id + client_secret CIFRADO (AES-256-GCM
 * em BYTEA com IV proprio) e o access_token de curta duracao (renovado quando
 * expira, sem refresh token). [platform] diz qual plataforma (NINETY_NINE/RAPPI).
 *
 * Estado operacional ([status], [consecutiveFailures], [lastSuccessfulPoll]) e
 * atualizado pelo poller/health. UNIQUE(company_id) garante 1 vinculo por company.
 *
 * Espelha db/control/migration/V9__open_delivery_tenant_config.sql. Colunas com
 * @Column explicito para casar exatamente com o snake_case da migration.
 */
@Entity
@Table(name = "open_delivery_tenant_config")
class OpenDeliveryTenantConfig(
    @Column(name = "company_id", nullable = false)
    var companyId: UUID,

    @Column(nullable = false, length = 15)
    @Enumerated(EnumType.STRING)
    var platform: OpenDeliveryPlatform,

    @Column(name = "base_url", nullable = false, length = 255)
    var baseUrl: String,

    @Column(name = "client_id", nullable = false, length = 120)
    var clientId: String,

    // client_secret cifrado (AES-256-GCM) + IV proprio. NOT NULL na migration.
    @Column(name = "client_secret_enc", nullable = false)
    var clientSecretEnc: ByteArray,

    @Column(name = "client_secret_iv", nullable = false)
    var clientSecretIv: ByteArray,

    @Column(name = "key_version", nullable = false)
    var keyVersion: Int = 1,

    @Column(name = "access_token_enc")
    var accessTokenEnc: ByteArray? = null,

    @Column(name = "access_token_iv")
    var accessTokenIv: ByteArray? = null,

    @Column(name = "token_expires_at")
    var tokenExpiresAt: Instant? = null,

    @Column(nullable = false, length = 12)
    @Enumerated(EnumType.STRING)
    var status: OpenDeliveryIntegrationStatus = OpenDeliveryIntegrationStatus.DISCONNECTED,

    @Column(name = "last_successful_poll")
    var lastSuccessfulPoll: Instant? = null,

    @Column(name = "consecutive_failures", nullable = false)
    var consecutiveFailures: Int = 0,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

/** Plataforma Open Delivery integrada. */
enum class OpenDeliveryPlatform {
    NINETY_NINE,
    RAPPI,
}

/**
 * Estado da integracao Open Delivery de um tenant (espelha o do iFood):
 *  - ACTIVE: pollando normalmente;
 *  - DEGRADED: falhas recentes, mas ainda tentando;
 *  - SUSPENDED: pausada (circuito aberto / acao manual);
 *  - DISCONNECTED: sem credenciais / nunca conectada (default).
 */
enum class OpenDeliveryIntegrationStatus {
    ACTIVE,
    DEGRADED,
    SUSPENDED,
    DISCONNECTED,
}

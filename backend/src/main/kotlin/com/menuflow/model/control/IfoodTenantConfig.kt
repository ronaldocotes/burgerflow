package com.menuflow.model.control

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Vinculo de um TENANT (company) a um merchant iFood, no banco de CONTROLE.
 *
 * Guarda os tokens OAuth do merchant (access/refresh) CIFRADOS (AES-256-GCM) em
 * BYTEA com IV proprio por token — a cifra e do Centuriao (chave fora do banco).
 * [appId] aponta para o [IfoodAppConfig] (PRIMARY/BACKUP) usado por este merchant.
 *
 * Estado operacional da integracao: [status], [consecutiveFailures] e
 * [lastSuccessfulPoll] sao atualizados pelo poller/health. UNIQUE(merchant_id) e
 * UNIQUE(company_id) garantem 1 merchant iFood <-> 1 company.
 *
 * Espelha db/control/migration/V8__ifood_tenant_config.sql.
 */
@Entity
@Table(name = "ifood_tenant_config")
class IfoodTenantConfig(
    @Column(name = "company_id", nullable = false)
    var companyId: UUID,

    // FK para ifood_app_config(id) — modelado como UUID simples (nao @ManyToOne)
    // para evitar carregamento lazy/proxy num registro de controle raramente lido.
    @Column(name = "app_id", nullable = false)
    var appId: UUID,

    @Column(name = "merchant_id", nullable = false, length = 64)
    var merchantId: String,

    @Column(name = "access_token_enc")
    var accessTokenEnc: ByteArray? = null,

    @Column(name = "access_token_iv")
    var accessTokenIv: ByteArray? = null,

    @Column(name = "refresh_token_enc")
    var refreshTokenEnc: ByteArray? = null,

    @Column(name = "refresh_token_iv")
    var refreshTokenIv: ByteArray? = null,

    @Column(name = "token_expires_at")
    var tokenExpiresAt: Instant? = null,

    @Column(name = "backup_authorized", nullable = false)
    var backupAuthorized: Boolean = false,

    @Column(nullable = false, length = 12)
    @Enumerated(EnumType.STRING)
    var status: IfoodIntegrationStatus = IfoodIntegrationStatus.DISCONNECTED,

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

/**
 * Estado da integracao iFood de um tenant:
 *  - ACTIVE: pollando normalmente;
 *  - DEGRADED: falhas recentes, mas ainda tentando;
 *  - SUSPENDED: pausada (ex.: circuito aberto por muito tempo / acao manual);
 *  - DISCONNECTED: sem tokens / nunca conectada (default).
 */
enum class IfoodIntegrationStatus {
    ACTIVE,
    DEGRADED,
    SUSPENDED,
    DISCONNECTED,
}

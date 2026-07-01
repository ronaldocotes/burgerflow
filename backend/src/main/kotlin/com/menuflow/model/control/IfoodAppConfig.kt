package com.menuflow.model.control

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Credencial da APLICACAO iFood, no banco de CONTROLE (nivel plataforma, nao por
 * tenant). Uma linha por app registrado no iFood: [role] PRIMARY e, opcionalmente,
 * BACKUP para failover.
 *
 * Os segredos (client_secret e app_token) ficam CIFRADOS (AES-256-GCM) em colunas
 * BYTEA, cada um com seu IV proprio; [keyVersion] permite rotacao de chave. A chave
 * de cifra NAO vive no banco — e responsabilidade do Centuriao (KMS/env). O texto
 * claro nunca e persistido nem logado.
 *
 * Espelha db/control/migration/V7__ifood_app_config.sql. Hibernate valida (nunca
 * altera) o schema do banco de controle no boot.
 */
@Entity
@Table(name = "ifood_app_config")
class IfoodAppConfig(
    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    var role: IfoodAppRole,

    @Column(name = "client_id", nullable = false, length = 120)
    var clientId: String,

    @Column(name = "client_secret_enc", nullable = false)
    var clientSecretEnc: ByteArray,

    @Column(name = "client_secret_iv", nullable = false)
    var clientSecretIv: ByteArray,

    @Column(name = "key_version", nullable = false)
    var keyVersion: Int = 1,

    @Column(nullable = false, length = 14)
    var cnpj: String,

    @Column(name = "app_token_enc")
    var appTokenEnc: ByteArray? = null,

    @Column(name = "app_token_iv")
    var appTokenIv: ByteArray? = null,

    @Column(name = "token_expires_at")
    var tokenExpiresAt: Instant? = null,

    @Column(nullable = false)
    var active: Boolean = false,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

/** Papel do app no iFood: PRIMARY (padrao) ou BACKUP (failover). */
enum class IfoodAppRole {
    PRIMARY,
    BACKUP,
}

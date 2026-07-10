package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Conta de anuncio da Meta conectada por um tenant (Fase 8.0). Vive no banco do
 * TENANT (db-per-tenant), entao nao tem coluna de escopo. Espelha
 * db/tenant/migration/V58__ad_account.sql.
 *
 * O token (System User Token) e guardado SOMENTE cifrado (token_enc + token_iv,
 * AES-256-GCM via IfoodTokenCipher). Ele NUNCA e exposto em DTO/resposta — o
 * AdAccountResponse so devolve metadados e um fingerprint (ultimos 4 do id externo).
 */
@Entity
@Table(name = "ad_account")
class AdAccount(
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var provider: AdProvider = AdProvider.META,

    /** account_id numerico da Meta, sem o prefixo "act_". */
    @Column(name = "external_account_id", nullable = false, length = 50)
    var externalAccountId: String,

    @Column(name = "account_name", length = 200)
    var accountName: String? = null,

    @Column(length = 10)
    var currency: String? = null,

    @Column(name = "timezone_name", length = 50)
    var timezoneName: String? = null,

    @Column(name = "page_id", length = 50)
    var pageId: String? = null,

    @Column(name = "page_name", length = 200)
    var pageName: String? = null,

    /** Token cifrado (AES-256-GCM). Nunca em claro; nunca devolvido em GET. */
    @Column(name = "token_enc", nullable = false)
    var tokenEnc: ByteArray,

    /** IV de 12 bytes emparelhado com [tokenEnc]. */
    @Column(name = "token_iv", nullable = false)
    var tokenIv: ByteArray,

    @Column(name = "token_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var tokenType: AdTokenType = AdTokenType.SYSTEM_USER,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: AdAccountStatus = AdAccountStatus.CONNECTED,

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,

    /** Usuario (banco de CONTROLE) que conectou. UUID sem relacao JPA (cross-db). */
    @Column(name = "connected_by_user_id")
    var connectedByUserId: UUID? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

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

/** Provedor de anuncios. So META nesta fase; enum aberto para 99/Google no futuro. */
enum class AdProvider { META }

/** Tipo do token guardado. So SYSTEM_USER no MVP (OAuth fica para fase madura). */
enum class AdTokenType { SYSTEM_USER, OAUTH }

/** Estado da conexao da conta. */
enum class AdAccountStatus { CONNECTED, ERROR, DISCONNECTED }

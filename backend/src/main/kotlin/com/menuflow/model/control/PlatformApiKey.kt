package com.menuflow.model.control

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Chave de API de um provedor externo, no banco de CONTROLE (nivel plataforma, SEM
 * escopo de tenant). MVP: [PlatformApiKeyProviderType.GOOGLE_MAPS], a chave usada em
 * distancia/geocode.
 *
 * O valor fica CIFRADO (AES-256-GCM) em [valueEnc]/[valueIv] (BYTEA); [keyVersion]
 * permite rotacao da chave de cifra. A chave de cifra NAO vive no banco (env
 * IFOOD_ENCRYPTION_KEY / Centuriao). O texto claro nunca e persistido nem logado.
 *
 * Invariante de negocio: no maximo UMA linha [active] por [provider] — garantida no
 * banco pelo indice unico PARCIAL ux_platform_api_key_provider_active (V16). Linhas
 * inativas sao historico de rotacao.
 *
 * Espelha db/control/migration/V16__platform_api_key.sql. Hibernate VALIDA (nunca
 * altera) o schema do banco de controle no boot.
 */
@Entity
@Table(name = "platform_api_key")
class PlatformApiKey(
    @Column(nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    var provider: PlatformApiKeyProviderType,

    @Column(name = "value_enc", nullable = false)
    var valueEnc: ByteArray,

    @Column(name = "value_iv", nullable = false)
    var valueIv: ByteArray,

    @Column(name = "key_version", nullable = false)
    var keyVersion: Int = 1,

    @Column(nullable = false)
    var active: Boolean = true,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "updated_by")
    var updatedBy: UUID? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
)

/**
 * Provedor logico da chave. MVP so tem GOOGLE_MAPS; o enum existe para permitir novos
 * provedores (ex.: OSRM hospedado, outra API) sem mudar o schema.
 */
enum class PlatformApiKeyProviderType {
    GOOGLE_MAPS,
}

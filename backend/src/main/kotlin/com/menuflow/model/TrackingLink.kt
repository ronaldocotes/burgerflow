package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Link rastreavel first-party (Fase 3.6). Vive no banco do TENANT (db-per-tenant),
 * entao nao precisa de coluna de escopo.
 *
 * [slug] e a chave natural curta (UNIQUE) usada na URL compartilhavel
 * (https://.../r/{slug}); o [destinationUrl] ja embute os parametros UTM. O
 * [clickCount] e incrementado de forma atomica (UPDATE ... SET click_count + 1)
 * a cada clique, separado da contagem de eventos para leitura barata no painel.
 */
@Entity
@Table(name = "tracking_links")
class TrackingLink(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "slug", nullable = false, length = 12, unique = true)
    var slug: String,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "source", nullable = false, length = 100)
    var source: String,

    @Column(name = "medium", length = 100)
    var medium: String? = null,

    @Column(name = "campaign", length = 100)
    var campaign: String? = null,

    @Column(name = "destination_url", nullable = false, columnDefinition = "text")
    var destinationUrl: String,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "click_count", nullable = false)
    var clickCount: Long = 0L,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

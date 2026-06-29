package com.menuflow.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Transferencia do atendimento do bot para um atendente humano (Fase 4.3). Vive no
 * banco do TENANT (db-per-tenant). Enquanto [resolved] = false, o bot fica em SILENCIO
 * total para [customerPhone]: o humano assumiu a conversa e o bot nao responde mais
 * (mesmo que o cliente continue escrevendo).
 *
 * O handoff e criado quando o cliente digita a palavra-chave de transferencia
 * (tenant_config.bot_handoff_keyword). O atendente encerra via POST /bot/handoffs/{id}/resolve.
 */
@Entity
@Table(name = "bot_handoffs")
data class BotHandoff(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Telefone (so digitos) do cliente que pediu atendimento humano. */
    @Column(name = "customer_phone", nullable = false, length = 30)
    val customerPhone: String,

    /** Mensagem do cliente que disparou o handoff (contexto para o atendente). */
    @Column(name = "last_bot_message", columnDefinition = "text")
    val lastBotMessage: String? = null,

    /** false = humano no comando (bot calado); true = atendimento encerrado. */
    @Column(name = "resolved", nullable = false)
    var resolved: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
)

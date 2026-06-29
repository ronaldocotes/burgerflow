package com.menuflow.model.control

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Pergunta canonica do golden set de avaliacao do Copiloto (Fase 4.2). Vive no banco
 * de CONTROLE (global): a mesma referencia vale para TODOS os tenants. O eval roda
 * estas perguntas contra um tenant real e compara as ferramentas usadas com as
 * esperadas. O controle roda ddl-auto=validate -> os @Column batem com a V6.
 */
@Entity
@Table(name = "ai_golden_questions")
data class AiGoldenQuestion(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "question", columnDefinition = "text", nullable = false)
    val question: String,

    /** JSON array de nomes de ferramentas esperadas, ex.: ["get_dre"]. */
    @Column(name = "expected_tools", columnDefinition = "text", nullable = false)
    val expectedTools: String,

    @Column(name = "category", nullable = false, length = 50)
    val category: String,

    @Column(name = "active", nullable = false)
    val active: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

package com.menuflow.repository.control

import com.menuflow.model.control.AiGoldenQuestion
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Golden set de avaliacao do Copiloto (banco de CONTROLE). Referencia global, igual
 * para todos os tenants.
 */
@Repository
interface AiGoldenQuestionRepository : JpaRepository<AiGoldenQuestion, UUID> {
    /** Perguntas ativas, ordenadas por categoria (estavel para o eval e o GET /ai/golden-set). */
    fun findByActiveTrueOrderByCategoryAscQuestionAsc(): List<AiGoldenQuestion>
}

package com.menuflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.dto.EvalResult
import com.menuflow.dto.EvalSummary
import com.menuflow.dto.GoldenQuestionResponse
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.control.AiGoldenQuestion
import com.menuflow.repository.control.AiGoldenQuestionRepository
import com.menuflow.repository.control.TenantRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Avaliacao (eval) do Copiloto contra o golden set (Fase 4.2). Operacao de PLATAFORMA
 * (SUPER_ADMIN, cross-tenant): roda as perguntas canonicas do banco de CONTROLE contra
 * um tenant REAL e mede se o copiloto roteou para as ferramentas esperadas.
 *
 * IMPORTANTE: o eval passa userRoles VAZIO para AiCopilotService.chat. Assim as
 * ferramentas de ACAO (create_coupon/schedule_campaign) sao registradas como "usadas"
 * (a comparacao de roteamento funciona) mas NAO executam a mutacao real — um eval
 * nunca cria cupom/campanha de verdade. O LLM real e chamado (nos testes ele e mockado).
 */
@Service
class AiEvalService(
    private val copilotService: AiCopilotService,
    private val goldenRepository: AiGoldenQuestionRepository,
    private val tenantRepository: TenantRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun goldenSet(): List<GoldenQuestionResponse> =
        goldenRepository.findByActiveTrueOrderByCategoryAscQuestionAsc().map { toResponse(it) }

    fun runEval(tenantSlug: String): EvalSummary {
        val tenant = tenantRepository.findBySlug(tenantSlug)
            ?: throw ResourceNotFoundException("Tenant nao encontrado: $tenantSlug")
        val questions = goldenRepository.findByActiveTrueOrderByCategoryAscQuestionAsc()

        val results = questions.map { q ->
            val expected = parseTools(q.expectedTools)
            val start = System.currentTimeMillis()
            val resp = copilotService.chat(
                tenantSlug = tenantSlug,
                tenantUuid = tenant.id!!,
                sessionId = "eval-${UUID.randomUUID()}",
                userMessage = q.question,
                actorUserId = null,
                userRoles = emptyList(), // eval nao executa acoes que mutam
            )
            val latencyMs = System.currentTimeMillis() - start
            // Passou se TODAS as ferramentas esperadas foram acionadas (o modelo pode
            // chamar extras; o que importa e nao deixar de chamar a correta).
            val passed = expected.isNotEmpty() && resp.toolsUsed.containsAll(expected)
            EvalResult(
                question = q.question,
                expectedTools = expected,
                actualTools = resp.toolsUsed,
                passed = passed,
                latencyMs = latencyMs,
                tokensUsed = resp.tokensUsed,
            )
        }

        val passed = results.count { it.passed }
        val passRate = if (results.isEmpty()) 0.0 else passed.toDouble() / results.size
        log.info("Eval do golden set no tenant {}: {}/{} ({}%)", tenantSlug, passed, results.size, (passRate * 100).toInt())
        return EvalSummary(totalQuestions = results.size, passed = passed, passRate = passRate, results = results)
    }

    private fun toResponse(q: AiGoldenQuestion) = GoldenQuestionResponse(
        id = q.id!!,
        question = q.question,
        expectedTools = parseTools(q.expectedTools),
        category = q.category,
        active = q.active,
    )

    private fun parseTools(json: String): List<String> = try {
        objectMapper.readValue(json, Array<String>::class.java).toList()
    } catch (e: Exception) {
        emptyList()
    }
}

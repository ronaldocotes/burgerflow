package com.menuflow

import com.menuflow.client.AiTool
import com.menuflow.client.ChatMessage
import com.menuflow.client.ChatResponse
import com.menuflow.client.LiteLLMClient
import com.menuflow.client.ToolCall
import com.menuflow.dto.TenantConfigUpdateRequest
import com.menuflow.exception.ForbiddenException
import com.menuflow.exception.TooManyRequestsException
import com.menuflow.repository.tenant.AiConversationRepository
import com.menuflow.service.AiCopilotService
import com.menuflow.service.TenantConfigService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * Copiloto do dono (Fase 4.1) contra um Postgres real (Testcontainers). O LiteLLMClient
 * e MOCKADO — nenhuma chamada HTTP sai. Cada caso usa seu proprio tenant (db isolado);
 * o teste NAO e @Transactional (cada chamada de servico commita), de modo que a leitura
 * do historico enxerga o que o chat persistiu.
 *
 * Cobre: copiloto desligado (403), limite diario (429), resposta sem tool-call
 * (persiste user+assistant), resposta com 1 tool-call (executa + reenvia ao LLM),
 * guarda de 5 iteracoes (nao laça) e geracao de sessionId quando nulo.
 */
class AiCopilotServiceTest @Autowired constructor(
    private val copilotService: AiCopilotService,
    private val tenantConfigService: TenantConfigService,
    private val aiConversationRepository: AiConversationRepository,
    private val tenantTx: TenantTestTx,
) : IntegrationTestBase() {

    @MockitoBean
    private lateinit var liteLLMClient: LiteLLMClient

    private lateinit var tenant: String
    private var tenantUuid: UUID = UUID.randomUUID()

    @AfterEach
    fun clear() = TenantContext.clear()

    /** Matcher any() compativel com parametros nao-nulos do Kotlin. */
    private fun <T> anyArg(): T = Mockito.any()

    private fun bind(): String {
        tenant = "ai_${UUID.randomUUID().toString().take(8)}"
        tenantUuid = UUID.randomUUID()
        TenantContext.set(tenant)
        return tenant
    }

    private fun enableAi(dailyLimit: Int = 30) {
        TenantContext.set(tenant)
        tenantConfigService.update(
            TenantConfigUpdateRequest(autoAcceptOrders = false, aiEnabled = true, aiDailyLimit = dailyLimit),
        )
    }

    private fun textResp(text: String) =
        ChatResponse(content = text, toolCalls = emptyList(), model = "test-model", promptTokens = 10, completionTokens = 5)

    private fun toolResp(tool: String) =
        ChatResponse(
            content = null,
            toolCalls = listOf(ToolCall(id = "call_1", name = tool, arguments = emptyMap(), argumentsJson = "{}")),
            model = "test-model",
            promptTokens = 8,
            completionTokens = 2,
        )

    /** Stub do LLM: respostas devolvidas em sequencia (a ultima repete). */
    private fun stubLlm(vararg responses: ChatResponse) {
        val first = responses.first()
        val rest = responses.drop(1).toTypedArray()
        Mockito.`when`(liteLLMClient.chat(anyArg<List<ChatMessage>>(), anyArg<List<AiTool>?>(), Mockito.anyInt()))
            .thenReturn(first, *rest)
    }

    private fun chat(sessionId: String?, message: String, roles: List<String> = listOf("ADMIN")) =
        copilotService.chat(
            tenantSlug = tenant,
            tenantUuid = tenantUuid,
            sessionId = sessionId,
            userMessage = message,
            actorUserId = UUID.randomUUID(),
            userRoles = roles,
        )

    @Test
    fun `copiloto desativado retorna erro 403-like`() {
        bind()
        // AI nasce desligada (V29 default ai_enabled=false; V13 ja semeia a linha).
        assertThrows(ForbiddenException::class.java) {
            chat(null, "Como vao as vendas?")
        }
        // Nem chegou a chamar o LLM.
        Mockito.verifyNoInteractions(liteLLMClient)
    }

    @Test
    fun `limite diario atingido retorna erro 429-like`() {
        bind()
        enableAi(dailyLimit = 1)
        stubLlm(textResp("Tudo certo!"))

        // 1a pergunta consome a unica do dia.
        chat("sess-rl", "primeira")
        // 2a pergunta: limite estourado.
        assertThrows(TooManyRequestsException::class.java) {
            chat("sess-rl", "segunda")
        }
    }

    @Test
    fun `resposta sem tool-call persiste user e assistant e retorna o texto`() {
        bind()
        enableAi()
        stubLlm(textResp("Suas vendas vao bem!"))

        val r = chat("sess3", "Como vao as vendas?")

        assertEquals("Suas vendas vao bem!", r.text)
        assertTrue(r.toolsUsed.isEmpty(), "nao usou ferramentas")

        TenantContext.set(tenant)
        val msgs = tenantTx.run { aiConversationRepository.findBySessionIdOrderByCreatedAtAsc("sess3") }
        assertEquals(2, msgs.size, "persistiu user + assistant")
        assertEquals("user", msgs[0].role)
        assertEquals("assistant", msgs[1].role)
        assertEquals("Suas vendas vao bem!", msgs[1].content)
    }

    @Test
    fun `resposta com tool-call executa a ferramenta e chama o LLM de novo`() {
        bind()
        enableAi()
        // 1o turno: pede a ferramenta; 2o turno: texto final.
        stubLlm(toolResp("get_rfv_summary"), textResp("Voce tem 0 clientes fieis no momento."))

        val r = chat("sess4", "Quantos clientes fieis eu tenho?")

        assertEquals("Voce tem 0 clientes fieis no momento.", r.text)
        assertTrue(r.toolsUsed.contains("get_rfv_summary"), "registrou a ferramenta usada")

        TenantContext.set(tenant)
        val msgs = tenantTx.run { aiConversationRepository.findBySessionIdOrderByCreatedAtAsc("sess4") }
        assertTrue(msgs.any { it.role == "tool" && it.toolName == "get_rfv_summary" }, "persistiu a mensagem tool")
        // LLM chamado duas vezes (turno da ferramenta + turno final).
        Mockito.verify(liteLLMClient, Mockito.times(2)).chat(anyArg<List<ChatMessage>>(), anyArg<List<AiTool>?>(), Mockito.anyInt())
    }

    @Test
    fun `guarda de 5 iteracoes nao entra em loop infinito`() {
        bind()
        enableAi()
        // LLM SEMPRE pede ferramenta (nunca conclui) -> a guarda corta em 5 rodadas.
        stubLlm(toolResp("get_rfv_summary"))

        val r = chat("sess5", "fica em loop")

        assertTrue(r.text.isNotBlank(), "devolve uma resposta de fallback, nao trava")
        Mockito.verify(liteLLMClient, Mockito.times(AiCopilotService.MAX_ITERATIONS))
            .chat(anyArg<List<ChatMessage>>(), anyArg<List<AiTool>?>(), Mockito.anyInt())
    }

    @Test
    fun `sessionId nulo gera um UUID`() {
        bind()
        enableAi()
        stubLlm(textResp("ok"))

        val r = chat(null, "oi")

        assertDoesNotThrow { UUID.fromString(r.sessionId) }
    }
}

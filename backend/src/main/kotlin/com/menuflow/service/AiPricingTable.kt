package com.menuflow.service

/**
 * Tabela de precos de LLMs em micros de USD por 1.000 tokens (1 USD = 1_000_000 micros).
 *
 * Usar micros inteiros evita ponto flutuante -- mesmo padrao de preco_centavos no resto
 * do MenuFlow. Os valores refletem precos publicos (lista price) por volta de 2026-07;
 * nao incluem descontos de volume ou contrato. Atualizar quando os providers anunciarem
 * mudancas de preco; o historico no banco fica inalterado (snapshot na gravacao).
 *
 * Modelos nao mapeados recebem o fallback conservador (5_000 input / 15_000 output),
 * que e o tier medio do mercado -- melhor superestimar do que esconder custo.
 */
object AiPricingTable {

    // Custo em micros de USD por 1.000 tokens de INPUT
    private val INPUT_COST_PER_1K = mapOf(
        "claude-sonnet-4-6"         to 3_000L,   // 3 USD/M tokens
        "claude-haiku-4-5"          to 800L,     // 0.80 USD/M
        "claude-opus-4-8"           to 15_000L,  // 15 USD/M
        "gpt-4o"                    to 5_000L,   // 5 USD/M
        "gpt-4o-mini"               to 150L,     // 0.15 USD/M
        "mistral-small-latest"      to 1_000L,   // ~1 USD/M
        "mistral-medium-latest"     to 2_700L,
        "mistral-large-latest"      to 8_000L,
    )

    // Custo em micros de USD por 1.000 tokens de OUTPUT
    private val OUTPUT_COST_PER_1K = mapOf(
        "claude-sonnet-4-6"         to 15_000L,  // 15 USD/M
        "claude-haiku-4-5"          to 4_000L,   // 4 USD/M
        "claude-opus-4-8"           to 75_000L,  // 75 USD/M
        "gpt-4o"                    to 15_000L,  // 15 USD/M
        "gpt-4o-mini"               to 600L,     // 0.60 USD/M
        "mistral-small-latest"      to 3_000L,
        "mistral-medium-latest"     to 8_100L,
        "mistral-large-latest"      to 24_000L,
    )

    private const val FALLBACK_INPUT  = 5_000L
    private const val FALLBACK_OUTPUT = 15_000L

    /**
     * Retorna o custo estimado em micros de USD para a combinacao de modelo e tokens.
     * Divisao inteira por 1000 -- fracoes de menos de 1 micro sao descartadas (OK para
     * contagem mensal acumulada, onde o erro e sub-centavo de USD).
     */
    fun estimateMicros(model: String, inputTokens: Long, outputTokens: Long): Long {
        val inCost  = (INPUT_COST_PER_1K[model]  ?: FALLBACK_INPUT)  * inputTokens  / 1_000
        val outCost = (OUTPUT_COST_PER_1K[model] ?: FALLBACK_OUTPUT) * outputTokens / 1_000
        return inCost + outCost
    }
}

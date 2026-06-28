package com.menuflow.client

import com.menuflow.exception.ServiceUnavailableException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Cliente HTTP da API Asaas (PIX). Pontos criticos:
 *
 *  - Autenticacao por header `access_token: <chave>` (NAO e Bearer). A chave vem
 *    de env (asaas.api-key) e e injetada como header padrao do RestClient; ela
 *    NUNCA e logada (o RestClient nao loga headers por padrao, e os logs de erro
 *    aqui usam so a mensagem da excecao).
 *  - Resiliencia: @Retry (transientes) + @CircuitBreaker (abre apos falhas
 *    repetidas, evitando martelar o Asaas e prender threads). O fallback converte
 *    a indisponibilidade num 503 limpo (ServiceUnavailableException), nunca num
 *    500 cru — assim o PDV recebe um erro tratavel ("PIX indisponivel, tente
 *    cartao/dinheiro").
 */
@Component
class AsaasClient(
    // Defaults inline porque o application.yml de teste SOMBREIA o main (o bloco
    // asaas: nao existe la); sem default, qualquer contexto de teste que construa
    // o bean real falharia ao resolver o placeholder. Em prod/dev o yml fornece.
    @Value("\${asaas.base-url:https://sandbox.asaas.com/api/v3}") baseUrl: String,
    @Value("\${asaas.api-key:}") apiKey: String,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient = builder
        .baseUrl(baseUrl)
        .defaultHeader("access_token", apiKey)
        .defaultHeader("Content-Type", "application/json")
        .build()

    @Retry(name = "asaas")
    @CircuitBreaker(name = "asaas", fallbackMethod = "createPaymentFallback")
    fun createPayment(req: AsaasPaymentRequest): AsaasPaymentResponse =
        client.post()
            .uri("/payments")
            .body(req)
            .retrieve()
            .body(AsaasPaymentResponse::class.java)
            ?: throw ServiceUnavailableException("Asaas: resposta vazia ao criar cobranca PIX")

    @Suppress("UNUSED_PARAMETER")
    fun createPaymentFallback(req: AsaasPaymentRequest, ex: Throwable): AsaasPaymentResponse {
        log.error("Asaas indisponivel ao criar cobranca PIX: {}", ex.message)
        throw ServiceUnavailableException("Pagamento PIX temporariamente indisponivel")
    }

    @Retry(name = "asaas")
    @CircuitBreaker(name = "asaas", fallbackMethod = "getPixQrFallback")
    fun getPixQr(asaasPaymentId: String): AsaasPixQrResponse =
        client.get()
            .uri("/payments/{id}/pixQrCode", asaasPaymentId)
            .retrieve()
            .body(AsaasPixQrResponse::class.java)
            ?: throw ServiceUnavailableException("Asaas: resposta vazia ao obter QR PIX")

    @Suppress("UNUSED_PARAMETER")
    fun getPixQrFallback(asaasPaymentId: String, ex: Throwable): AsaasPixQrResponse {
        log.error("Asaas indisponivel ao obter QR PIX: {}", ex.message)
        throw ServiceUnavailableException("Pagamento PIX temporariamente indisponivel")
    }

    @Retry(name = "asaas")
    @CircuitBreaker(name = "asaas", fallbackMethod = "findPaymentFallback")
    fun findPayment(asaasPaymentId: String): AsaasPaymentResponse =
        client.get()
            .uri("/payments/{id}", asaasPaymentId)
            .retrieve()
            .body(AsaasPaymentResponse::class.java)
            ?: throw ServiceUnavailableException("Asaas: resposta vazia ao consultar cobranca")

    @Suppress("UNUSED_PARAMETER")
    fun findPaymentFallback(asaasPaymentId: String, ex: Throwable): AsaasPaymentResponse {
        log.error("Asaas indisponivel ao consultar cobranca: {}", ex.message)
        throw ServiceUnavailableException("Consulta de pagamento PIX indisponivel")
    }

    @Retry(name = "asaas")
    @CircuitBreaker(name = "asaas", fallbackMethod = "createCustomerFallback")
    fun createCustomer(req: AsaasCustomerRequest): AsaasCustomerResponse =
        client.post()
            .uri("/customers")
            .body(req)
            .retrieve()
            .body(AsaasCustomerResponse::class.java)
            ?: throw ServiceUnavailableException("Asaas: resposta vazia ao criar customer")

    @Suppress("UNUSED_PARAMETER")
    fun createCustomerFallback(req: AsaasCustomerRequest, ex: Throwable): AsaasCustomerResponse {
        log.error("Asaas indisponivel ao criar customer: {}", ex.message)
        throw ServiceUnavailableException("Pagamento PIX temporariamente indisponivel")
    }
}

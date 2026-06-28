package com.menuflow.controller

import com.menuflow.dto.AsaasWebhookBody
import com.menuflow.service.PixPaymentService
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Webhook do Asaas. PUBLICO (sem JWT) — permitido em SecurityConfig na rota /webhooks.
 * O tenant e identificado pelo path {tenantSlug}, e o TenantContext e vinculado aqui
 * para rotear a escrita ao banco daquele restaurante.
 *
 * SEGURANCA: o Asaas NAO assina o webhook (sem HMAC). A defesa pratica e que o
 * processamento so tem efeito quando `payment.id` existe no banco do tenant — e um id
 * nao adivinhavel gerado pelo Asaas (ver PixPaymentService.handleWebhook). Mesmo
 * assim, em producao recomenda-se reforcar com IP allowlist do Asaas e/ou um token
 * secreto por tenant no path/header. FOLLOW-UP registrado.
 *
 * Sempre responde 200 rapidamente: erro interno e logado mas NAO devolve 4xx/5xx para
 * o Asaas (evita reentrega agressiva); cobrancas perdidas sao recuperadas pela
 * reconciliacao/consulta de status.
 */
@RestController
@RequestMapping("/webhooks")
class AsaasWebhookController(private val service: PixPaymentService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/asaas/{tenantSlug}")
    fun receiveWebhook(
        @PathVariable tenantSlug: String,
        @RequestBody body: AsaasWebhookBody,
    ): ResponseEntity<Void> {
        try {
            TenantContext.set(tenantSlug)
            service.handleWebhook(body)
        } catch (ex: Exception) {
            log.error("Erro ao processar webhook Asaas (tenant {}): {}", tenantSlug, ex.message)
        } finally {
            TenantContext.clear()
        }
        return ResponseEntity.ok().build()
    }
}

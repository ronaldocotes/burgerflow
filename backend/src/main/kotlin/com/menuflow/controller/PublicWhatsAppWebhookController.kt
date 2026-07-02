package com.menuflow.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.menuflow.dto.WahaWebhookBody
import com.menuflow.service.WhatsAppBotService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook INBOUND do WAHA para o bot WhatsApp (Fase 4.3). PUBLICO (sem JWT) — permitido
 * em SecurityConfig nas rotas publicas (prefixo /public). O tenant vem do path {tenantSlug}.
 *
 * SEMPRE devolve 200 imediatamente: o WAHA reentrega se nao receber 2xx, entao
 * processamos de forma ASSINCRONA ([WhatsAppBotService.handleIncomingAsync]) e nunca
 * propagamos erro. Filtramos aqui o que nao deve virar resposta do bot: eventos que nao
 * sao 'message' e mensagens enviadas pelo proprio numero (fromMe). A idempotencia por
 * messageId vive no servico (dedup 24h), cobrindo a reentrega do WAHA.
 *
 * SEGURANCA: o body cru e usado para verificar a assinatura HMAC opcional
 * (X-WAHA-Signature). Se `menuflow.bot.webhook-secret` estiver configurado e a
 * assinatura nao bater, ignoramos silenciosamente (200, sem processar). Sem secret
 * configurado, a verificacao e pulada (WAHA Core nao assina por padrao); a defesa
 * complementar e o rate-limit por IP (PublicOrderRateLimitFilter cobre esta rota).
 * FOLLOW-UP: secret por tenant em vez de global.
 */
@RestController
@RequestMapping("/public")
class PublicWhatsAppWebhookController(
    private val botService: WhatsAppBotService,
    private val objectMapper: ObjectMapper,
    @Value("\${menuflow.bot.webhook-secret:}") private val webhookSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/{tenantSlug}/whatsapp/webhook")
    fun receive(
        @PathVariable tenantSlug: String,
        @RequestBody rawBody: String,
        @RequestHeader(value = "X-WAHA-Signature", required = false) signature: String?,
    ): ResponseEntity<Void> {
        try {
            // Assinatura HMAC opcional (so verifica se configurada).
            if (webhookSecret.isNotBlank() && !signatureValid(rawBody, signature)) {
                log.warn("Assinatura de webhook invalida (tenant {}); ignorando", tenantSlug)
                return ResponseEntity.ok().build()
            }

            val parsed = objectMapper.readValue(rawBody, WahaWebhookBody::class.java)
            val payload = parsed.payload
            // So mensagens recebidas (nao eco do proprio numero) viram resposta do bot.
            if (parsed.event != "message" || payload == null || payload.fromMe) {
                return ResponseEntity.ok().build()
            }
            val from = payload.from?.takeIf { it.isNotBlank() } ?: return ResponseEntity.ok().build()
            // G3: ignorar mensagens de grupo (@g.us ou participant != null) — B2 fara o roteamento.
            if (from.endsWith("@g.us") || payload.participant != null) {
                log.debug("Mensagem de grupo ignorada: {} — aguardar B2", from)
                return ResponseEntity.ok().build()
            }
            val body = payload.body ?: ""
            val messageId = payload.id ?: ""

            // Processa fora do thread HTTP (WAHA recebe 200 ja).
            botService.handleIncomingAsync(tenantSlug, from, body, messageId)
        } catch (e: Exception) {
            // Nunca devolve 4xx/5xx ao WAHA (evita reentrega agressiva).
            log.error("Erro ao receber webhook do bot (tenant {}): {}", tenantSlug, e.message)
        }
        return ResponseEntity.ok().build()
    }

    /** HMAC-SHA256 do corpo cru em hex, comparado em tempo constante. */
    private fun signatureValid(rawBody: String, signature: String?): Boolean {
        if (signature.isNullOrBlank()) return false
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(webhookSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val computed = mac.doFinal(rawBody.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            constantTimeEquals(computed, signature.trim().removePrefix("sha256="))
        } catch (e: Exception) {
            false
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

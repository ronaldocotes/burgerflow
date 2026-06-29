package com.menuflow.service

import com.menuflow.model.OrderStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.client.RestClient

/**
 * Pontos de notificacao ao cliente por WhatsApp (Fase 2.4). Mapeia os marcos do
 * ciclo de vida do pedido (cozinha + despacho de entrega) para um aviso enxuto —
 * so os marcos abaixo disparam, para nao lotar o cliente.
 */
enum class OrderNotificationKind { PREPARING, READY, OUT_FOR_DELIVERY, DELIVERED }

/**
 * Fato de dominio publicado quando o status de um pedido atinge um marco que merece
 * avisar o cliente. Carrega SOMENTE dados primitivos (telefone, marco, nome do
 * restaurante) — nada de entidade JPA — porque e consumido APOS o commit, fora da
 * transacao, sem acesso ao banco do tenant.
 */
data class OrderStatusNotification(
    val customerPhone: String?,
    val kind: OrderNotificationKind,
    val restaurantName: String,
)

/**
 * Envia notificacoes de status de pedido ao cliente via WAHA (WhatsApp HTTP API).
 *
 * Decisoes de projeto:
 *  - Fail-open: qualquer falha no WAHA e LOGADA e engolida; nunca propaga, nunca
 *    quebra o fluxo do pedido. O aviso e best-effort.
 *  - Disparo AFTER_COMMIT: o envio acontece depois que a transacao que mudou o
 *    status comita. Assim nao seguramos a conexao do banco durante a chamada HTTP
 *    externa e nao avisamos o cliente se a transacao der rollback.
 *  - Opt-in por pedido: so envia se o pedido tiver telefone do cliente.
 *  - Rate-limit natural: cada marco unico do pedido dispara uma unica vez (a
 *    maquina de estados nao reentra no mesmo status), entao nao ha laco de reenvio.
 */
@Service
class WhatsAppService(
    @Value("\${waha.base-url:http://127.0.0.1:3030}") baseUrl: String,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Default inline + cliente proprio (mesmo padrao do AsaasClient): o yml de teste
    // SOMBREIA o main e nao tem o bloco waha:, entao sem default o contexto de teste
    // falharia ao resolver o placeholder.
    private val client: RestClient = builder.baseUrl(baseUrl).build()

    /**
     * Consome o fato de dominio APOS o commit (AFTER_COMMIT). So roda se houver
     * transacao e ela comitar. Roda no mesmo thread, mas a conexao do banco ja foi
     * devolvida ao pool — a chamada HTTP nao prende recurso de banco.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderStatusNotification(event: OrderStatusNotification) {
        send(event)
    }

    /**
     * Monta o chatId e despacha o texto para o WAHA. Normaliza o telefone para
     * digitos e garante o DDI 55 (sem duplicar quando o cliente ja informou). Sem
     * telefone -> nao envia (opt-in). Falha -> log + segue (fail-open).
     */
    fun send(event: OrderStatusNotification) {
        dispatch(event.customerPhone, buildMessage(event.kind, event.restaurantName))
    }

    /**
     * Aviso de recompensa de fidelidade desbloqueada (Fase 3.3). Fail-open e opt-in
     * (sem telefone, nao envia), igual aos demais avisos. Chamado pelo LoyaltyService
     * APOS o credito ser comitado.
     */
    fun sendLoyaltyReward(customerPhone: String?, rewardDescription: String) {
        dispatch(customerPhone, "🎉 Você ganhou uma recompensa! $rewardDescription")
    }

    /**
     * Normaliza o telefone (digitos + DDI 55) e despacha o texto para o WAHA. Sem
     * telefone -> nao envia (opt-in). Falha -> log + segue (fail-open). Compartilhado
     * por todos os avisos para garantir a mesma normalizacao e a mesma garantia de
     * fail-open.
     */
    private fun dispatch(customerPhone: String?, text: String) {
        val digits = customerPhone?.replace(Regex("[^0-9]"), "").orEmpty()
        if (digits.isEmpty()) return // opt-in: sem telefone nao notifica

        val msisdn = if (digits.startsWith("55")) digits else "55$digits"
        val chatId = "$msisdn@c.us"

        try {
            client.post()
                .uri("/api/sendText")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("chatId" to chatId, "text" to text))
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            // Fail-open: o aviso e best-effort; nunca derruba o fluxo do pedido.
            log.warn("WhatsApp send failed: {}", e.message)
        }
    }

    private fun buildMessage(kind: OrderNotificationKind, restaurantName: String): String = when (kind) {
        OrderNotificationKind.PREPARING ->
            "✅ Seu pedido foi aceito! Estamos preparando tudo com carinho. 👨‍🍳"
        OrderNotificationKind.READY ->
            "🎉 Seu pedido esta pronto! Pode retirar."
        OrderNotificationKind.OUT_FOR_DELIVERY ->
            "🛵 Seu pedido saiu para entrega. Em breve chega ate voce!"
        OrderNotificationKind.DELIVERED ->
            "📦 Pedido entregue! Obrigado por escolher o $restaurantName. Avalie sua experiencia."
    }

    companion object {
        /**
         * Marco de cozinha que merece aviso, ou null (silencioso). O modelo real de
         * OrderStatus e PENDING -> PREPARING -> READY -> DELIVERED -> CANCELLED: nao
         * existe ACCEPTED/IN_PREPARATION separados, entao PREPARING carrega o aviso
         * de "aceito + preparando". PENDING e CANCELLED nao notificam.
         */
        fun kindFor(status: OrderStatus): OrderNotificationKind? = when (status) {
            OrderStatus.PREPARING -> OrderNotificationKind.PREPARING
            OrderStatus.READY -> OrderNotificationKind.READY
            OrderStatus.DELIVERED -> OrderNotificationKind.DELIVERED
            OrderStatus.PENDING, OrderStatus.CANCELLED -> null
        }
    }
}

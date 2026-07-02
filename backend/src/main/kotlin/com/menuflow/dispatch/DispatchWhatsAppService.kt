package com.menuflow.dispatch

import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryOffer
import com.menuflow.model.Order
import com.menuflow.model.TenantConfig
import com.menuflow.service.WhatsAppService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Mensageria do despacho por grupo (Fase B2). Concentra TODOS os textos que trafegam no
 * WhatsApp do despacho — grupo dos motoboys, DM ao vencedor, aviso ao cliente, escalacao
 * ao dono e recrutamento do freelancer — para garantir a mesma regra de PII em um so lugar.
 *
 * INVARIANTE DE PRIVACIDADE (LGPD):
 *  - o GRUPO nunca ve endereco nem telefone do cliente: so bairro (anonimizado), valor e
 *    codigo de aceite;
 *  - o endereco completo + telefone do cliente + link do mapa so vao em DM ao motoboy
 *    VENCEDOR (sendDmToWinner);
 *  - o ACK no grupo confirma o fechamento sem revelar dado de cliente.
 *
 * TRANSPORTE: recebe entidades JA carregadas (nao toca banco) e apenas formata + despacha.
 * Isso mantem as chamadas HTTP ao WAHA FORA de qualquer transacao (quem chama carrega os
 * dados numa tx e chama estes metodos depois do commit). Grupos usam [WhatsAppService.sendRaw]
 * (chatId cru, aceita @g.us); DMs usam [WhatsAppService.sendCampaign] (normaliza para @c.us).
 * Tudo fail-open no WhatsAppService: falha no WAHA e logada, nunca propaga.
 */
@Service
class DispatchWhatsAppService(
    private val whatsAppService: WhatsAppService,
    @Value("\${menuflow.app.base-url:}") private val appBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Formata centavos como "R$ 12,50" (virgula decimal, pt-BR). Nunca usa float para armazenar. */
    private fun brl(cents: Long?): String = "R$ %.2f".format((cents ?: 0L) / 100.0).replace('.', ',')

    /** Distancia em km a partir dos metros (ou do distanceKm legado); "?" se desconhecida. */
    private fun kmLabel(offer: DeliveryOffer): String {
        val meters = offer.distanceMeters ?: offer.distanceKm?.let { (it * 1000).toLong() }
        return meters?.let { "%.1f".format(it / 1000.0).replace('.', ',') } ?: "?"
    }

    /** Oferta no GRUPO — sem PII, so bairro + valor + codigo de aceite. */
    fun sendGroupOffer(offer: DeliveryOffer, config: TenantConfig, tenantSlug: String) {
        val group = offer.groupJid?.takeIf { it.isNotBlank() } ?: return
        val nome = config.restaurantName?.takeIf { it.isNotBlank() } ?: tenantSlug
        val timeoutMin = maxOf(1, config.dispatchOfferTimeoutSeconds / 60)
        val bairro = offer.neighborhoodLabel?.takeIf { it.isNotBlank() } ?: "bairro nao informado"
        val texto = buildString {
            append("🛵 CORRIDA ${offer.acceptCode} — $nome\n")
            append("📍 $bairro (~${kmLabel(offer)} km)\n")
            append("💰 Voce recebe: ${brl(offer.payoutCents)}\n")
            append("⏱️ Valida por $timeoutMin min\n")
            append("Responda: ACEITO ${offer.acceptCode}")
        }
        whatsAppService.sendRaw(group, texto, config.wahaPrimaryPhone)
    }

    /** DM ao motoboy VENCEDOR — aqui SIM o endereco completo + telefone do cliente + mapa. */
    fun sendDmToWinner(offer: DeliveryOffer, driver: DeliveryDriver, order: Order, config: TenantConfig) {
        val phone = driver.phone.takeIf { it.isNotBlank() } ?: return
        val cliente = order.deliveryRecipientName?.takeIf { it.isNotBlank() } ?: "Cliente"
        val fone = order.customerPhone ?: order.deliveryPhone ?: "-"
        val endereco = buildString {
            append(order.deliveryStreet ?: "")
            order.deliveryNumber?.takeIf { it.isNotBlank() }?.let { append(", $it") }
            order.deliveryComplement?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            order.deliveryNeighborhood?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
        }.ifBlank { "Endereco nao informado" }
        val texto = buildString {
            append("✅ Corrida ${offer.acceptCode} e sua!\n")
            append("👤 $cliente • 📞 $fone\n")
            append("🏠 $endereco\n")
            order.deliveryReference?.takeIf { it.isNotBlank() }?.let { append("Referencia: $it\n") }
            append("🗺️ ${mapsLink(order)}\n")
            append("Corrida: ${brl(offer.payoutCents)}")
        }
        whatsAppService.sendCampaign(phone, texto, config.wahaPrimaryPhone)
    }

    /** Link do Google Maps: por coordenadas quando houver; senao pelo endereco em texto. */
    private fun mapsLink(order: Order): String {
        val lat = order.deliveryLat
        val lng = order.deliveryLng
        val base = "https://www.google.com/maps/dir/?api=1&travelmode=driving&destination="
        return if (lat != null && lng != null) {
            "$base$lat,$lng"
        } else {
            val addr = listOfNotNull(
                order.deliveryStreet, order.deliveryNumber, order.deliveryNeighborhood, order.deliveryCity,
            ).joinToString(", ").ifBlank { "" }
            base + URLEncoder.encode(addr, StandardCharsets.UTF_8)
        }
    }

    /** ACK no GRUPO confirmando o fechamento — sem endereco nem telefone do cliente. */
    fun sendGroupAck(offer: DeliveryOffer, driver: DeliveryDriver, config: TenantConfig) {
        val group = offer.groupJid?.takeIf { it.isNotBlank() } ?: return
        val texto = "✅ Corrida ${offer.acceptCode} fechada. Obrigado ${driver.name}!"
        whatsAppService.sendRaw(group, texto, config.wahaPrimaryPhone)
    }

    /** Aviso ao CLIENTE de que a entrega tem motoboy, com link de acompanhamento. */
    fun sendClientNotification(order: Order, config: TenantConfig) {
        val phone = order.customerPhone ?: order.deliveryPhone ?: return
        if (appBaseUrl.isBlank()) {
            log.debug("menuflow.app.base-url vazio; nao envio link de acompanhamento ao cliente")
            return
        }
        val link = "${appBaseUrl.trimEnd('/')}/acompanhar/${order.id}"
        val texto = "🛵 Seu pedido tem entregador! Acompanhe: $link"
        whatsAppService.sendCampaign(phone, texto, config.wahaPrimaryPhone)
    }

    /** Escalacao ao DONO quando ninguem aceitou a corrida (esgotou as tentativas). */
    fun escalateToOwner(offer: DeliveryOffer, order: Order, config: TenantConfig) {
        val phone = config.ownerPhone?.takeIf { it.isNotBlank() }
        if (phone == null) {
            log.warn(
                "Corrida #{} sem motoboy e sem owner_phone configurado; escalacao apenas logada",
                order.orderNumber,
            )
            return
        }
        val texto = "⚠️ Nenhum motoboy aceitou a corrida #${order.orderNumber}. Intervencao necessaria."
        whatsAppService.sendCampaign(phone, texto, config.wahaPrimaryPhone)
    }

    /** DM de recrutamento ao freelancer provisional apos a 1a entrega concluida. */
    fun sendRecruitmentDm(driver: DeliveryDriver, config: TenantConfig) {
        val phone = driver.phone.takeIf { it.isNotBlank() } ?: return
        val token = driver.signupToken ?: return
        if (appBaseUrl.isBlank()) {
            log.debug("menuflow.app.base-url vazio; nao envio convite de cadastro ao motoboy")
            return
        }
        val link = "${appBaseUrl.trimEnd('/')}/motoboy/cadastro/$token"
        val texto = "Parabens pela primeira entrega! 🛵\n" +
            "Cadastre-se e ganhe prioridade nas corridas + extrato de ganhos:\n$link"
        whatsAppService.sendCampaign(phone, texto, config.wahaPrimaryPhone)
    }

    /** Resposta curta ao participante que tentou aceitar uma corrida ja fechada. */
    fun sendAlreadyTaken(participantJid: String, code: String, config: TenantConfig) {
        whatsAppService.sendRaw(participantJid, "Corrida $code ja foi aceita por outro entregador.", config.wahaPrimaryPhone)
    }

    /** Resposta curta ao participante quando o codigo nao existe / expirou. */
    fun sendCodeNotFound(participantJid: String, code: String, config: TenantConfig) {
        whatsAppService.sendRaw(participantJid, "Codigo $code nao encontrado ou expirado.", config.wahaPrimaryPhone)
    }
}

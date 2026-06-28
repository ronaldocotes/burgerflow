package com.menuflow

import com.menuflow.model.OrderStatus
import com.menuflow.service.OrderNotificationKind
import com.menuflow.service.OrderStatusNotification
import com.menuflow.service.WhatsAppService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/**
 * Teste unitario do WhatsAppService (Fase 2.4) — sem Spring/Testcontainers. O WAHA e
 * simulado com MockRestServiceServer ligado ao RestClient.Builder, entao nenhuma
 * chamada HTTP real sai. Cobre: (1) marco -> texto + chatId corretos; (2) fail-open
 * (erro do WAHA nao propaga); (3) opt-in (telefone vazio nao envia); (4) DDI 55 nao
 * e duplicado quando o cliente ja informou.
 */
class WhatsAppServiceTest {

    private val baseUrl = "http://127.0.0.1:3030"

    /** Monta o servico com um WAHA simulado; devolve (servico, servidor mock). */
    private fun build(): Pair<WhatsAppService, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val service = WhatsAppService(baseUrl, builder)
        return service to server
    }

    @Test
    fun `kindFor mapeia somente os marcos que notificam`() {
        assertEquals(OrderNotificationKind.PREPARING, WhatsAppService.kindFor(OrderStatus.PREPARING))
        assertEquals(OrderNotificationKind.READY, WhatsAppService.kindFor(OrderStatus.READY))
        assertEquals(OrderNotificationKind.DELIVERED, WhatsAppService.kindFor(OrderStatus.DELIVERED))
        assertNull(WhatsAppService.kindFor(OrderStatus.PENDING))
        assertNull(WhatsAppService.kindFor(OrderStatus.CANCELLED))
    }

    @Test
    fun `PREPARING envia aviso de preparo para o chatId correto`() {
        val (service, server) = build()
        server.expect(requestTo("$baseUrl/api/sendText"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.chatId").value("5511999998888@c.us"))
            .andExpect(jsonPath("$.text").value(containsString("preparando")))
            .andRespond(withSuccess())

        service.send(
            OrderStatusNotification("(11) 99999-8888", OrderNotificationKind.PREPARING, "Burger CR"),
        )
        server.verify()
    }

    @Test
    fun `READY envia aviso de pronto`() {
        val (service, server) = build()
        server.expect(requestTo("$baseUrl/api/sendText"))
            .andExpect(jsonPath("$.text").value(containsString("pronto")))
            .andRespond(withSuccess())

        service.send(OrderStatusNotification("11999998888", OrderNotificationKind.READY, "Burger CR"))
        server.verify()
    }

    @Test
    fun `OUT_FOR_DELIVERY envia aviso de saiu para entrega`() {
        val (service, server) = build()
        server.expect(requestTo("$baseUrl/api/sendText"))
            .andExpect(jsonPath("$.text").value(containsString("entrega")))
            .andRespond(withSuccess())

        service.send(OrderStatusNotification("11999998888", OrderNotificationKind.OUT_FOR_DELIVERY, "Burger CR"))
        server.verify()
    }

    @Test
    fun `DDI 55 ja informado pelo cliente nao e duplicado`() {
        val (service, server) = build()
        server.expect(requestTo("$baseUrl/api/sendText"))
            .andExpect(jsonPath("$.chatId").value("5511999998888@c.us"))
            .andRespond(withSuccess())

        service.send(OrderStatusNotification("5511999998888", OrderNotificationKind.READY, "Burger CR"))
        server.verify()
    }

    @Test
    fun `falha do WAHA nao propaga (fail-open)`() {
        val (service, server) = build()
        server.expect(requestTo("$baseUrl/api/sendText"))
            .andRespond(withServerError())

        assertDoesNotThrow {
            service.send(OrderStatusNotification("11999998888", OrderNotificationKind.READY, "Burger CR"))
        }
        server.verify()
    }

    @Test
    fun `telefone vazio nao dispara nenhuma chamada (opt-in)`() {
        val (service, server) = build()
        // Nenhuma expectativa registrada: qualquer chamada falharia a verificacao.
        service.send(OrderStatusNotification(null, OrderNotificationKind.READY, "Burger CR"))
        service.send(OrderStatusNotification("   ", OrderNotificationKind.DELIVERED, "Burger CR"))
        server.verify()
    }
}

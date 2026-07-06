package com.menuflow

import com.menuflow.dto.OrderItemRequest
import com.menuflow.dto.PdvChannel
import com.menuflow.dto.PdvOrderCreateRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import java.util.concurrent.ExecutionException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc

/**
 * KDS end-to-end (Sprint 2): a STOMP client authenticated with the tenant JWT
 * subscribes to /topic/kds/{slug}; changing an order's status over REST must push
 * the event to that topic in under a second. Proves: WS auth via signed token,
 * tenant-scoped topic, and broadcast-on-status-change.
 */
@AutoConfigureMockMvc
class KdsWebSocketTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var slug: String

    @BeforeEach
    fun seed() {
        slug = "kds_${UUID.randomUUID().toString().take(8)}"
        val tenant = tenantRepository.save(Tenant(slug = slug, displayName = "KDS Burger"))
        userRepository.save(
            User(
                tenantId = tenant.id!!,
                email = "cook@$slug.com",
                passwordHash = passwordEncoder.encode("pass1234"),
                firstName = "Cook",
                role = UserRole.ADMIN,
            ),
        )
    }

    private fun login(): String {
        val body = objectMapper.writeValueAsString(
            mapOf("email" to "cook@$slug.com", "password" to "pass1234", "tenantSlug" to slug),
        )
        val res = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andReturn()
        return objectMapper.readTree(res.response.contentAsString).get("token").asText()
    }

    private fun stompClient(): WebSocketStompClient {
        // Raw WebSocket (no SockJS) so there is no preliminary HTTP /info probe;
        // the STOMP CONNECT frame carries the Bearer token for authentication.
        val client = WebSocketStompClient(StandardWebSocketClient())
        // The server broadcasts JSON (application/json); use a Jackson converter so
        // the frame handler can deserialize it into a Map.
        client.messageConverter = MappingJackson2MessageConverter()
        return client
    }

    @Test
    fun `status change broadcasts a KDS event to the tenant topic`() {
        val token = login()

        // Create an order over the PDV so there is something to advance.
        val product = objectMapper.writeValueAsString(
            mapOf("categoryId" to UUID.randomUUID().toString(), "sku" to "KDS-X", "name" to "Burger", "priceCents" to 2000),
        )
        val productId = objectMapper.readTree(
            mockMvc.perform(
                post("/products").header("Authorization", "Bearer $token")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(product),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("id").asText()

        val orderBody = objectMapper.writeValueAsString(
            PdvOrderCreateRequest(
                items = listOf(OrderItemRequest(productId = UUID.fromString(productId), quantity = 1)),
                channel = PdvChannel.DINE_IN,
            ),
        )
        val orderId = objectMapper.readTree(
            mockMvc.perform(
                post("/pdv/orders").header("Authorization", "Bearer $token")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(orderBody),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("id").asText()

        // Connect STOMP with the Bearer token on the CONNECT frame and subscribe.
        // O handshake usa o caminho REAL de producao (/api/v1/ws): o endpoint /ws
        // vive sob o server.servlet.context-path=/api/v1.
        val received = LinkedBlockingDeque<Map<*, *>>()
        val connectHeaders = StompHeaders().apply { add("Authorization", "Bearer $token") }
        val url = "ws://localhost:$port/api/v1/ws"
        val session: StompSession = stompClient()
            .connectAsync(
                url,
                org.springframework.web.socket.WebSocketHttpHeaders(),
                connectHeaders,
                object : StompSessionHandlerAdapter() {},
            )
            .get(5, TimeUnit.SECONDS)

        session.subscribe(
            "/topic/kds/$slug",
            object : StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = Map::class.java
                override fun handleFrame(headers: StompHeaders, payload: Any?) {
                    received.offer(payload as Map<*, *>)
                }
            },
        )
        // Give the broker a moment to register the subscription.
        Thread.sleep(300)

        // Advance status PENDING -> PREPARING over REST; expect a broadcast.
        val statusBody = objectMapper.writeValueAsString(mapOf("status" to "PREPARING"))
        mockMvc.perform(
            put("/orders/$orderId/status").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(statusBody),
        ).andExpect(status().isOk)

        val event = received.poll(2, TimeUnit.SECONDS)
        assertNotNull(event, "Expected a KDS event on /topic/kds/$slug within 2s")
        assertEquals(orderId, event!!["orderId"])
        assertEquals("PREPARING", event["status"])
        assertEquals(1, (event["items"] as List<*>).size)

        session.disconnect()
    }

    @Test
    fun `creating an order broadcasts a KDS event to the tenant topic`() {
        val token = login()

        // Produto para o pedido.
        val product = objectMapper.writeValueAsString(
            mapOf("categoryId" to UUID.randomUUID().toString(), "sku" to "KDS-N", "name" to "X-Novo", "priceCents" to 2500),
        )
        val productId = objectMapper.readTree(
            mockMvc.perform(
                post("/products").header("Authorization", "Bearer $token")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(product),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("id").asText()

        // Assina o topico ANTES de criar o pedido: o evento esperado e o da CRIACAO
        // (bug: pedido novo do PDV nao aparecia na cozinha sem refresh manual).
        val received = LinkedBlockingDeque<Map<*, *>>()
        val session: StompSession = stompClient()
            .connectAsync(
                "ws://localhost:$port/api/v1/ws",
                org.springframework.web.socket.WebSocketHttpHeaders(),
                StompHeaders().apply { add("Authorization", "Bearer $token") },
                object : StompSessionHandlerAdapter() {},
            )
            .get(5, TimeUnit.SECONDS)
        session.subscribe(
            "/topic/kds/$slug",
            object : StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = Map::class.java
                override fun handleFrame(headers: StompHeaders, payload: Any?) {
                    received.offer(payload as Map<*, *>)
                }
            },
        )
        Thread.sleep(300)

        // Cria o pedido via PDV; o evento deve chegar APOS o commit, com o status
        // inicial (PENDING — sem aceite automatico configurado neste tenant).
        val orderBody = objectMapper.writeValueAsString(
            PdvOrderCreateRequest(
                items = listOf(OrderItemRequest(productId = UUID.fromString(productId), quantity = 1)),
                channel = PdvChannel.DINE_IN,
            ),
        )
        val orderId = objectMapper.readTree(
            mockMvc.perform(
                post("/pdv/orders").header("Authorization", "Bearer $token")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(orderBody),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("id").asText()

        val event = received.poll(2, TimeUnit.SECONDS)
        assertNotNull(event, "Expected a KDS event on /topic/kds/$slug after order creation")
        assertEquals(orderId, event!!["orderId"])
        assertEquals("PENDING", event["status"])
        assertEquals(1, (event["items"] as List<*>).size)

        session.disconnect()
    }

    @Test
    fun `STOMP connect without a token is rejected`() {
        // The server interceptor throws on a CONNECT lacking a Bearer token, which
        // closes the connection: the connect future completes exceptionally.
        val ex = assertThrows(ExecutionException::class.java) {
            stompClient()
                .connectAsync(
                    "ws://localhost:$port/api/v1/ws",
                    org.springframework.web.socket.WebSocketHttpHeaders(),
                    StompHeaders(), // no Authorization header
                    object : StompSessionHandlerAdapter() {},
                )
                .get(5, TimeUnit.SECONDS)
        }
        assertNotNull(ex.cause)
    }

    @Test
    fun `cannot subscribe to another tenant's KDS topic`() {
        val token = login()
        val session = stompClient()
            .connectAsync(
                "ws://localhost:$port/api/v1/ws",
                org.springframework.web.socket.WebSocketHttpHeaders(),
                StompHeaders().apply { add("Authorization", "Bearer $token") },
                object : StompSessionHandlerAdapter() {},
            )
            .get(5, TimeUnit.SECONDS)

        val received = LinkedBlockingDeque<Map<*, *>>()
        // Subscribe to a DIFFERENT tenant's topic; the interceptor must block it.
        session.subscribe(
            "/topic/kds/some_other_tenant",
            object : StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = Map::class.java
                override fun handleFrame(headers: StompHeaders, payload: Any?) {
                    received.offer(payload as Map<*, *>)
                }
            },
        )
        Thread.sleep(300)
        // No event for the other tenant should ever reach this client.
        assertNull(received.poll(1, TimeUnit.SECONDS))
    }
}

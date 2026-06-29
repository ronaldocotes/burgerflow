package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Estado de um carrinho/pedido pendente de pagamento, para a recuperacao de carrinho
 * abandonado (Fase 3.5). Criada quando um pedido nasce PENDENTE de pagamento e com
 * telefone do cliente; um job periodico (CartRecoveryService) envia a mensagem de
 * recuperacao apos um atraso configuravel.
 *
 * Vive no banco do TENANT (db-per-tenant) — nao ha coluna de escopo, cada conexao ja
 * aterrissa no restaurante certo. Aponta para orders(id) por FK.
 *
 * Ciclo de vida do status:
 *  - ACTIVE    -> recem-criada, aguardando (pedido ainda nao pago, sem mensagem);
 *  - SENT      -> mensagem de recuperacao enviada (continua sem pagamento);
 *  - RECOVERED -> o pedido foi pago (OrderPaidEvent); sucesso da recuperacao;
 *  - EXPIRED   -> passou o prazo (cart_recovery_expiry_hours) sem pagamento; encerrada.
 */
@Entity
@Table(name = "cart_sessions")
class CartSession(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Pedido pendente que originou esta comanda de recuperacao. */
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    /** Telefone do cliente (alvo do WhatsApp); pode ser nulo (sem recuperacao possivel). */
    @Column(name = "customer_phone", length = 20)
    val customerPhone: String? = null,

    /** Total do pedido em centavos (snapshot para a mensagem). */
    @Column(name = "total_cents", nullable = false)
    val totalCents: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: CartSessionStatus = CartSessionStatus.ACTIVE,

    /** Momento em que a mensagem de recuperacao foi enviada (status SENT). */
    @Column(name = "recovery_message_sent_at")
    var recoveryMessageSentAt: Instant? = null,

    /** Momento em que o pedido foi pago (status RECOVERED). */
    @Column(name = "recovered_at")
    var recoveredAt: Instant? = null,

    /** Momento em que o carrinho expirou sem pagamento (status EXPIRED). */
    @Column(name = "expired_at")
    var expiredAt: Instant? = null,

    /**
     * Carimbo de criacao. updatable=false: o valor passado no INSERT sobrevive (permite
     * o teste backdatar a criacao para exercitar atraso/expiracao).
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

enum class CartSessionStatus { ACTIVE, RECOVERED, SENT, EXPIRED }

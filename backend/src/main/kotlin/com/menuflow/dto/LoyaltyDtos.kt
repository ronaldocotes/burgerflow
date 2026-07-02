package com.menuflow.dto

import com.menuflow.model.LoyaltyTransaction
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Estado de fidelidade de um cliente (Fase 3.3). Exposto em
 * GET /customers/{id}/loyalty.
 *  - loyaltyPoints: saldo atual (após descontar punches já desbloqueados).
 *  - rewardThreshold: pontos para 1 recompensa.
 *  - progress: pontos no punch atual (loyaltyPoints % threshold).
 *  - punches: recompensas desbloqueadas e ainda NÃO resgatadas.
 *  - transactions: últimas 10 movimentações (extrato).
 */
data class LoyaltyStatusResponse(
    val loyaltyPoints: Int,
    val rewardThreshold: Int,
    val progress: Int,
    val punches: Int,
    /** ID do primeiro punch disponível; null se punches==0. Usado pelo frontend para chamar /redeem/{id}. */
    val pendingRewardId: UUID?,
    val transactions: List<LoyaltyTransactionResponse>,
)

data class LoyaltyTransactionResponse(
    val id: UUID,
    val pointsDelta: Int,
    val reason: String,
    val description: String?,
    val orderId: UUID?,
    val createdAt: Instant,
) {
    companion object {
        fun from(t: LoyaltyTransaction) =
            LoyaltyTransactionResponse(
                id = t.id!!,
                pointsDelta = t.pointsDelta,
                reason = t.reason,
                description = t.description,
                orderId = t.orderId,
                createdAt = t.createdAt,
            )
    }
}

/**
 * Ajuste manual de pontos (ADMIN/MANAGER). delta pode ser negativo (remover); o
 * saldo nunca fica negativo (coerce >= 0 no serviço). description é obrigatória
 * para deixar a trilha do ajuste auditável.
 */
data class LoyaltyAdjustRequest(
    val delta: Int,
    @field:NotBlank
    @field:Size(max = 200)
    val description: String,
)

/**
 * Sumário gerencial do programa de fidelidade para um período [from, to]
 * (ambos inclusivos, fuso America/Sao_Paulo). Retornado em
 * GET /loyalty/summary?from={date}&to={date}.
 *
 *  - activeCustomers: clientes com pontos > 0 AGORA (snapshot do momento; não
 *    depende do período pois saldos são acumulados).
 *  - totalPointsIssued: soma dos créditos (ORDER_PAID e MANUAL_ADJUST positivos)
 *    com createdAt no período.
 *  - totalRewardsRedeemed: quantidade de punches RESGATADOS (redeemedAt) no período.
 *  - totalPointsRedeemed: soma dos debitados por REWARD_REDEEMED no período
 *    (proxy do custo do programa — pontos "gastos").
 */
data class LoyaltySummaryResponse(
    val activeCustomers: Long,
    val totalPointsIssued: Long,
    val totalRewardsRedeemed: Long,
    val totalPointsRedeemed: Long,
)

package com.menuflow.dto

import com.fasterxml.jackson.annotation.JsonAlias
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Teto sensato para valores monetarios em centavos das tarifas/acertos (G6):
 * R$ 1.000.000,00. Barra entrada absurda e, junto do teto de dias/metros, mantem
 * os produtos (dias x diaria, entregas x valor, metros x tarifa) longe do overflow
 * de Long. Nao e um limite de negocio real, e um guarda-corpo anti-erro/anti-fraude.
 */
const val MAX_MONEY_CENTS: Long = 100_000_000L

/**
 * DTOs do acerto financeiro de entregadores (Fase 2.5). Dinheiro SEMPRE em
 * centavos. [driverId] referencia delivery_drivers.id (o entregador do tenant),
 * NAO um usuario do banco de controle.
 */

// --- Entregadores ---

/**
 * Resumo do entregador para o frontend montar as URLs do acerto. [id] e o
 * DeliveryDriver.id (= orders.driver_id), o MESMO driverId usado em
 * /drivers/{driverId}/config e nos acertos — NAO o id do usuario de controle.
 */
data class DeliveryDriverResponse(
    val id: UUID,
    val name: String,
    val phone: String?,
    val isActive: Boolean,
    /** User (banco de controle, papel DRIVER) vinculado ao entregador; null = sem acesso ao app. */
    val userId: UUID?,
    /**
     * Tipo de remuneracao do entregador: FROTA (contratado, acerto por dias+entregas+km)
     * ou FREELANCER (entrou pelo grupo, repasse por soma de corridas). Fonte de verdade
     * de DeliveryDriver.driverType — a tela de acerto ramifica por este campo, nao mais
     * pela mera presenca de DriverConfig.
     */
    val driverType: String,
)

// --- Configuracao de remuneracao ---

data class DriverConfigRequest(
    @field:PositiveOrZero @field:Max(MAX_MONEY_CENTS) val dailyRateCents: Long,
    @field:PositiveOrZero @field:Max(MAX_MONEY_CENTS) val perDeliveryCents: Long,
    @field:PositiveOrZero @field:Max(MAX_MONEY_CENTS) val perKmCents: Long,
    @field:Size(max = 500) val notes: String? = null,
)

data class DriverConfigResponse(
    val driverId: UUID,
    val dailyRateCents: Long,
    val perDeliveryCents: Long,
    val perKmCents: Long,
    val notes: String?,
)

// --- Acertos ---

data class OpenSettlementRequest(
    // G4: o front envia o id do entregador sob a chave "userId" (o DeliveryDriverResponse
    // expoe o driverId em .id, mas a tela historicamente serializa como "userId").
    // @JsonAlias aceita AMBAS as chaves ("driverId" e "userId") mapeando para este campo,
    // que sempre e o delivery_drivers.id — sem afrouxar nada nem exigir mudanca no front.
    @JsonAlias("userId")
    val driverId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    @field:Size(max = 500) val notes: String? = null,
)

data class CloseSettlementRequest(
    @field:PositiveOrZero @field:Max(366) val workingDays: Int,
    /**
     * G2: km agora e DISTANCIA (metros), NAO centavos prontos. Override manual/auditavel
     * do eixo por-km da FROTA: quando presente, o servidor usa este valor; quando null,
     * soma orders.delivery_distance_meters do periodo (ou 0). O servidor multiplica pela
     * tarifa perKmCents da config — o cliente nunca manda o valor em centavos. Teto 1000km
     * (1.000.000 m) so como guarda-corpo anti-erro; ignorado no acerto FREELANCER.
     */
    @field:PositiveOrZero @field:Max(1_000_000) val kmOverrideMeters: Long? = null,
    @field:Size(max = 500) val notes: String? = null,
)

data class DriverSettlementResponse(
    val id: UUID,
    val driverId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val deliveriesCount: Int,
    val workingDays: Int,
    val dailyTotalCents: Long,
    val deliveryTotalCents: Long,
    val kmTotalCents: Long,
    /** Metros que originaram o eixo km da FROTA (sistema ou override); null no FREELANCER. */
    val kmTotalMeters: Long?,
    /** Repasse total do FREELANCER (soma dos payouts das corridas DELIVERED); 0 na FROTA. */
    val payoutTotalCents: Long,
    val grossTotalCents: Long,
    /** Tipo de remuneracao congelado no fechamento: FROTA | FREELANCER. */
    val settlementType: String,
    /**
     * FREELANCER: quantas ofertas aceitas do periodo estao sem payout_cents definido
     * (contaram como 0, sem bloquear o fechamento — D-C). O front usa para avisar
     * "N corridas sem valor definido". 0 na FROTA ou fora do fechamento.
     */
    val offersWithoutPayout: Int,
    val status: String,
    val closedAt: Instant?,
    val notes: String?,
)

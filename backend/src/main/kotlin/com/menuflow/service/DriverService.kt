package com.menuflow.service

import com.menuflow.dto.CloseSettlementRequest
import com.menuflow.dto.DeliveryDriverResponse
import com.menuflow.dto.DriverConfigRequest
import com.menuflow.dto.DriverConfigResponse
import com.menuflow.dto.DriverSettlementResponse
import com.menuflow.dto.OpenSettlementRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DriverConfig
import com.menuflow.model.DriverSettlement
import com.menuflow.model.DriverSettlementStatus
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.DriverConfigRepository
import com.menuflow.repository.tenant.DriverSettlementRepository
import com.menuflow.repository.tenant.OrderRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Acerto financeiro de entregadores (Fase 2.5). Tudo no banco do TENANT (escopo
 * garantido pelo datasource roteado). Dinheiro SEMPRE em centavos.
 *
 * O "entregador" e a entidade DeliveryDriver (id = orders.driver_id); a config de
 * remuneracao e o acerto sao chaveados por esse driver_id, NAO por usuario do
 * banco de controle — assim o COUNT de entregas casa com orders.driver_id.
 *
 * Fechar um acerto congela o periodo: conta as entregas DELIVERED do periodo,
 * aplica a config (diaria x dias + valor x entregas + km informado) e grava
 * status CLOSED (imutavel; reabrir nao e suportado).
 */
@Service
class DriverService(
    private val driverConfigRepository: DriverConfigRepository,
    private val settlementRepository: DriverSettlementRepository,
    private val driverRepository: DeliveryDriverRepository,
    private val orderRepository: OrderRepository,
    private val deliveryOfferRepository: DeliveryOfferRepository,
    private val auditLogService: AuditLogService,
) {
    // Fuso do negocio para converter o periodo (DATE) em limites instantaneos
    // de dia, consistente com o resto do sistema (KDS, caixa).
    private val zone = ZoneId.of("America/Sao_Paulo")

    // Tipo de entregador (espelha DeliveryDriver.driverType, string). FROTA = 3 eixos;
    // FREELANCER = soma dos repasses das corridas do grupo (issue #3).
    private val FREELANCER = "FREELANCER"

    // --- Entregadores ---

    /**
     * Lista os entregadores deste tenant (ativos e inativos), ordenados por nome.
     * O [DeliveryDriverResponse.id] e o driverId usado em /drivers/{driverId}/config
     * e nos acertos — e o que o frontend precisa para montar as URLs (substitui o
     * GET /users?role=DELIVERY, que devolvia o id do usuario de controle, incompativel).
     * Inclui inativos com a flag isActive para o frontend filtrar/sinalizar.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun listDrivers(): List<DeliveryDriverResponse> =
        driverRepository.findAllByOrderByNameAsc().map { toDriverResponse(it) }

    // --- Configuracao de remuneracao ---

    @Transactional("tenantTransactionManager", readOnly = true)
    fun getConfig(driverId: UUID): DriverConfigResponse =
        driverConfigRepository.findByDriverId(driverId)?.let { toConfigResponse(it) }
            ?: throw ResourceNotFoundException("Entregador sem configuracao de remuneracao")

    /** Upsert da config de remuneracao (PUT). Exige que o entregador exista. */
    @Transactional("tenantTransactionManager")
    fun upsertConfig(driverId: UUID, actorId: UUID, req: DriverConfigRequest): DriverConfigResponse {
        loadDriver(driverId)
        val existing = driverConfigRepository.findByDriverId(driverId)
        val config = if (existing != null) {
            existing.dailyRateCents = req.dailyRateCents
            existing.perDeliveryCents = req.perDeliveryCents
            existing.perKmCents = req.perKmCents
            existing.notes = req.notes
            existing
        } else {
            DriverConfig(
                driverId = driverId,
                dailyRateCents = req.dailyRateCents,
                perDeliveryCents = req.perDeliveryCents,
                perKmCents = req.perKmCents,
                notes = req.notes,
            )
        }
        val saved = driverConfigRepository.save(config)
        auditLogService.log(
            action = "driver_config.upsert",
            entity = "driver_config",
            entityId = saved.id,
            after = mapOf(
                "driverId" to driverId,
                "dailyRateCents" to req.dailyRateCents,
                "perDeliveryCents" to req.perDeliveryCents,
                "perKmCents" to req.perKmCents,
            ),
            actorUserId = actorId,
        )
        return toConfigResponse(saved)
    }

    // --- Acertos ---

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(driverId: UUID?, status: DriverSettlementStatus?, pageable: Pageable): Page<DriverSettlementResponse> {
        val page = when {
            driverId != null && status != null -> settlementRepository.findByDriverIdAndStatus(driverId, status, pageable)
            driverId != null -> settlementRepository.findByDriverId(driverId, pageable)
            status != null -> settlementRepository.findByStatus(status, pageable)
            else -> settlementRepository.findAll(pageable)
        }
        return page.map { toResponse(it) }
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(id: UUID): DriverSettlementResponse =
        settlementRepository.findById(id)
            .map { toResponse(it) }
            .orElseThrow { ResourceNotFoundException("Acerto nao encontrado") }

    /** Abre um acerto OPEN para o entregador. As entregas sao contadas no fechamento. */
    @Transactional("tenantTransactionManager")
    fun openSettlement(actorId: UUID, req: OpenSettlementRequest): DriverSettlementResponse {
        loadDriver(req.driverId)
        if (req.periodEnd.isBefore(req.periodStart)) {
            throw BusinessException("Periodo invalido: fim antes do inicio")
        }
        // Checagem previa (mensagem amigavel) + indice parcial (corrida real).
        if (settlementRepository.existsByDriverIdAndStatus(req.driverId, DriverSettlementStatus.OPEN)) {
            throw ConflictException("Ja existe um acerto aberto para este entregador")
        }
        val saved = try {
            settlementRepository.save(
                DriverSettlement(
                    driverId = req.driverId,
                    periodStart = req.periodStart,
                    periodEnd = req.periodEnd,
                    notes = req.notes,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // Dois opens concorrentes: o indice parcial barra o segundo.
            throw ConflictException("Ja existe um acerto aberto para este entregador")
        }
        auditLogService.log(
            action = "driver_settlement.open",
            entity = "driver_settlement",
            entityId = saved.id,
            after = mapOf(
                "driverId" to req.driverId,
                "periodStart" to req.periodStart.toString(),
                "periodEnd" to req.periodEnd.toString(),
            ),
            actorUserId = actorId,
        )
        return toResponse(saved)
    }

    /**
     * Fecha o acerto: congela o periodo aplicando a regra do tipo do entregador
     * (issue #3). Ramifica por driverType e SNAPSHOTA o tipo usado (settlementType)
     * para o acerto nao mudar se o tipo do motoboy mudar depois.
     *
     *  - FROTA: 3 eixos server-side (diaria x dias + valor x entregas + km x tarifa),
     *    exige config de remuneracao (senao 400 — fechar tudo zero por config esquecida
     *    seria erro silencioso). Corrige G2: km = round(metros/1000 x perKmCents), a
     *    partir de uma DISTANCIA (do sistema ou override), nunca centavos do cliente.
     *  - FREELANCER: soma dos payout_cents das corridas aceitas com pedido DELIVERED no
     *    periodo (nao aplica os 3 eixos). NAO exige config.
     */
    @Transactional("tenantTransactionManager")
    fun closeSettlement(id: UUID, actorId: UUID, req: CloseSettlementRequest): DriverSettlementResponse {
        val settlement = settlementRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("Acerto nao encontrado") }
        if (settlement.status != DriverSettlementStatus.OPEN) {
            throw ConflictException("Acerto ja fechado")
        }
        val driver = loadDriver(settlement.driverId)

        // Periodo (DATE) -> limites instantaneos de dia em America/Sao_Paulo:
        // [inicio do dia de periodStart, inicio do dia seguinte a periodEnd).
        val from: Instant = settlement.periodStart.atStartOfDay(zone).toInstant()
        val to: Instant = settlement.periodEnd.plusDays(1).atStartOfDay(zone).toInstant()

        // Snapshot do tipo usado no fechamento (congela a regra).
        settlement.settlementType = driver.driverType
        var offersWithoutPayout = 0

        if (driver.driverType == FREELANCER) {
            val (payoutTotal, offersCount, withoutPayout) =
                freelancerPayout(settlement.driverId, from, to)
            offersWithoutPayout = withoutPayout
            // FREELANCER nao usa os 3 eixos: apenas o repasse das corridas.
            settlement.deliveriesCount = offersCount
            settlement.workingDays = 0
            settlement.dailyTotalCents = 0
            settlement.deliveryTotalCents = 0
            settlement.kmTotalCents = 0
            settlement.kmTotalMeters = null
            settlement.payoutTotalCents = payoutTotal
        } else {
            // FROTA: exige config (senao 400).
            val config = driverConfigRepository.findByDriverId(settlement.driverId)
                ?: throw BusinessException("Configure a remuneracao do entregador antes de fechar o acerto")
            val deliveries = orderRepository.countDeliveriesByDriverAndPeriod(settlement.driverId, from, to)
            // km: override manual (metros) quando presente; senao a distancia somada do
            // sistema (orders.delivery_distance_meters); senao 0. Server-side multiplica
            // pela tarifa perKmCents (corrige G2 — nunca recebe centavos prontos).
            val kmMeters = req.kmOverrideMeters
                ?: orderRepository.sumDeliveryDistanceByDriverAndPeriod(settlement.driverId, from, to)
            settlement.deliveriesCount = deliveries.toInt()
            settlement.workingDays = req.workingDays
            settlement.deliveryTotalCents = deliveries * config.perDeliveryCents
            settlement.dailyTotalCents = req.workingDays.toLong() * config.dailyRateCents
            settlement.kmTotalCents = kmCents(kmMeters, config.perKmCents)
            settlement.kmTotalMeters = kmMeters
            settlement.payoutTotalCents = 0
        }

        settlement.status = DriverSettlementStatus.CLOSED
        settlement.closedAt = Instant.now()
        settlement.closedByUserId = actorId
        req.notes?.let { settlement.notes = it }

        val saved = settlementRepository.save(settlement)
        auditLogService.log(
            action = "driver_settlement.close",
            entity = "driver_settlement",
            entityId = saved.id,
            after = mapOf(
                "settlementType" to saved.settlementType,
                "deliveriesCount" to saved.deliveriesCount,
                "workingDays" to saved.workingDays,
                "dailyTotalCents" to saved.dailyTotalCents,
                "deliveryTotalCents" to saved.deliveryTotalCents,
                "kmTotalCents" to saved.kmTotalCents,
                "kmTotalMeters" to saved.kmTotalMeters,
                "payoutTotalCents" to saved.payoutTotalCents,
                "offersWithoutPayout" to offersWithoutPayout,
                "grossTotalCents" to grossOf(saved),
            ),
            actorUserId = actorId,
        )
        return toResponse(saved, offersWithoutPayout)
    }

    /**
     * Repasse do FREELANCER (payoutTotal, offersCount, offersWithoutPayout) do periodo.
     * A query soma payout_cents (NULL = 0, D-C), conta as corridas DELIVERED (D-A) e as
     * que estao sem payout definido. Le a unica linha agregada e converte com defesa de
     * tipo (SUM/COUNT do Hibernate podem vir como Long/BigInteger).
     */
    private data class FreelancerPayout(val payoutTotal: Long, val offersCount: Int, val offersWithoutPayout: Int)

    private fun freelancerPayout(driverId: UUID, from: Instant, to: Instant): FreelancerPayout {
        val row = deliveryOfferRepository.sumFreelancerPayoutByDriverAndPeriod(driverId, from, to).first()
        return FreelancerPayout(
            payoutTotal = (row[0] as Number).toLong(),
            offersCount = (row[1] as Number).toInt(),
            offersWithoutPayout = (row[2] as Number).toInt(),
        )
    }

    // Eixo por-km da FROTA (G2): centavos = round(metros/1000 x tarifa_por_km).
    // BigDecimal + HALF_UP para nunca perder centavo por float.
    private fun kmCents(meters: Long, perKmCents: Long): Long =
        BigDecimal.valueOf(meters)
            .multiply(BigDecimal.valueOf(perKmCents))
            .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP)
            .toLong()

    // --- helpers ---

    /** Carrega o entregador deste tenant (senao 404). */
    private fun loadDriver(driverId: UUID): DeliveryDriver =
        driverRepository.findById(driverId)
            .orElseThrow { ResourceNotFoundException("Entregador nao encontrado") }

    // Bruto calculado em memoria (daily+delivery+km+payout): evita reler a coluna
    // gerada do banco, que ficaria stale na mesma transacao apos o save.
    private fun grossOf(s: DriverSettlement): Long =
        s.dailyTotalCents + s.deliveryTotalCents + s.kmTotalCents + s.payoutTotalCents

    private fun toDriverResponse(d: DeliveryDriver) = DeliveryDriverResponse(
        id = d.id!!,
        name = d.name,
        phone = d.phone,
        isActive = d.active,
        userId = d.userId,
        driverType = d.driverType,
    )

    private fun toConfigResponse(c: DriverConfig) = DriverConfigResponse(
        driverId = c.driverId,
        dailyRateCents = c.dailyRateCents,
        perDeliveryCents = c.perDeliveryCents,
        perKmCents = c.perKmCents,
        notes = c.notes,
    )

    // offersWithoutPayout so tem valor no fechamento FREELANCER (advisory ao front); nas
    // leituras (list/get) fica 0 — nao e persistido (o acerto ja esta congelado).
    private fun toResponse(s: DriverSettlement, offersWithoutPayout: Int = 0) = DriverSettlementResponse(
        id = s.id!!,
        driverId = s.driverId,
        periodStart = s.periodStart,
        periodEnd = s.periodEnd,
        deliveriesCount = s.deliveriesCount,
        workingDays = s.workingDays,
        dailyTotalCents = s.dailyTotalCents,
        deliveryTotalCents = s.deliveryTotalCents,
        kmTotalCents = s.kmTotalCents,
        kmTotalMeters = s.kmTotalMeters,
        payoutTotalCents = s.payoutTotalCents,
        grossTotalCents = grossOf(s),
        settlementType = s.settlementType,
        offersWithoutPayout = offersWithoutPayout,
        status = s.status.name,
        closedAt = s.closedAt,
        notes = s.notes,
    )
}

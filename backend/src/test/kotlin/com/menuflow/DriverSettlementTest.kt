package com.menuflow

import com.menuflow.dto.CloseSettlementRequest
import com.menuflow.dto.DriverConfigRequest
import com.menuflow.dto.OpenSettlementRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.DeliveryOffer
import com.menuflow.model.DeliveryOfferStatus
import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.DeliveryOfferRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.service.DriverService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Acerto financeiro de entregadores (issue #3, sobre a base da Fase 2.5) contra um
 * Postgres real (Testcontainers). Prova:
 *  - FROTA: 3 eixos server-side (diaria x dias + valor x entregas + km x tarifa),
 *    so entregas DELIVERED do periodo e do entregador certo;
 *  - REGRESSAO G2: o eixo km persiste km x perKmCents (NAO metros/centavos crus) —
 *    testado com perKm != R$1,00 (R$2,00 e R$0,50) para o teste nao mascarar o bug;
 *  - FROTA km do sistema: sem override, soma orders.delivery_distance_meters;
 *  - FREELANCER: soma dos payout das ofertas aceitas com pedido DELIVERED; pedido
 *    cancelado NAO conta (D-A); payout NULL conta 0 e offersWithoutPayout reflete (D-C);
 *  - SNAPSHOT: acerto fechado como FREELANCER nao muda se o driverType virar FROTA;
 *  - segundo acerto OPEN do mesmo entregador -> 409; fechar FROTA sem config -> 400.
 *
 * Cada caso usa seu PROPRIO tenant (db isolado). Nao e @Transactional: cada save
 * commita, entao open() enxerga as entregas semeadas antes do close().
 */
class DriverSettlementTest @Autowired constructor(
    private val driverService: DriverService,
    private val driverRepository: DeliveryDriverRepository,
    private val orderRepository: OrderRepository,
    private val offerRepository: DeliveryOfferRepository,
) : IntegrationTestBase() {

    private val zone = ZoneId.of("America/Sao_Paulo")

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun newDriver(
        name: String = "Entregador",
        active: Boolean = true,
        driverType: String = "FROTA",
    ): UUID {
        val saved = driverRepository.save(
            DeliveryDriver(
                name = name,
                // Telefone unico por driver: a V41 adicionou indice UNICO parcial em phone.
                phone = "5511" + UUID.randomUUID().toString().filter { it.isDigit() }.take(9).padEnd(9, '0'),
                active = active,
                tenantId = UUID.randomUUID(),
                driverType = driverType,
            ),
        )
        return saved.id!!
    }

    @Test
    fun `listDrivers - returns all drivers ordered by name with active flag`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)

        val carlosId = newDriver("Carlos", active = true)
        val anaId = newDriver("Ana", active = false)
        val brunoId = newDriver("Bruno", active = true)

        val drivers = driverService.listDrivers()

        assertEquals(3, drivers.size)
        assertEquals(listOf("Ana", "Bruno", "Carlos"), drivers.map { it.name })
        assertEquals(listOf(anaId, brunoId, carlosId), drivers.map { it.id })
        assertEquals(false, drivers.first { it.id == anaId }.isActive)
        assertEquals(true, drivers.first { it.id == carlosId }.isActive)
    }

    /** Persiste um pedido carimbado com o entregador, completado em [completedAt]. */
    private fun seedDelivered(
        driverId: UUID?,
        status: OrderStatus,
        completedAt: Instant?,
        distanceMeters: Long? = null,
    ): UUID = orderRepository.save(
        Order(
            orderNumber = "D-${UUID.randomUUID().toString().take(10)}",
            status = status,
            subtotalCents = 1000,
            totalCents = 1000,
            driverId = driverId,
            completedAt = completedAt,
            deliveryDistanceMeters = distanceMeters,
        ),
    ).id!!

    /** Oferta ACEITA por [driverId] para [orderId], com [payout] (null = sem valor). */
    private fun seedAcceptedOffer(driverId: UUID, orderId: UUID, payout: Long?) {
        offerRepository.save(
            DeliveryOffer(
                orderId = orderId,
                driverId = driverId,
                status = DeliveryOfferStatus.ACCEPTED,
                feeCents = 500,
                expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                payoutCents = payout,
                acceptedByDriverId = driverId,
                acceptedAt = Instant.now(),
            ),
        )
    }

    @Test
    fun `FROTA full flow - three axes with correct totals and gross`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        val driverId = newDriver()
        val otherDriver = newDriver("Outro")

        // Remuneracao: diaria R$50,00 + R$3,00 por entrega + R$2,00 por km.
        driverService.upsertConfig(
            driverId,
            actor,
            DriverConfigRequest(dailyRateCents = 5_000, perDeliveryCents = 300, perKmCents = 200),
        )

        val now = Instant.now()
        repeat(10) { seedDelivered(driverId, OrderStatus.DELIVERED, now) }
        // Ruido que NAO pode ser contado:
        seedDelivered(driverId, OrderStatus.PREPARING, now)
        seedDelivered(otherDriver, OrderStatus.DELIVERED, now)
        seedDelivered(driverId, OrderStatus.DELIVERED, now.minus(5, ChronoUnit.DAYS))

        val today = LocalDate.now(zone)
        val opened = driverService.openSettlement(
            actor,
            OpenSettlementRequest(driverId = driverId, periodStart = today, periodEnd = today),
        )
        assertEquals("OPEN", opened.status)

        // Fecha: 5 dias trabalhados + override manual de km = 10.000 m (10 km).
        val closed = driverService.closeSettlement(
            opened.id,
            actor,
            CloseSettlementRequest(workingDays = 5, kmOverrideMeters = 10_000),
        )

        assertEquals("CLOSED", closed.status)
        assertEquals("FROTA", closed.settlementType)
        assertEquals(10, closed.deliveriesCount, "so as 10 entregas DELIVERED deste entregador no periodo")
        assertEquals(5, closed.workingDays)
        assertEquals(3_000, closed.deliveryTotalCents, "10 entregas x R$3,00")
        assertEquals(25_000, closed.dailyTotalCents, "5 dias x R$50,00")
        assertEquals(2_000, closed.kmTotalCents, "10 km x R$2,00")
        assertEquals(10_000, closed.kmTotalMeters)
        assertEquals(0, closed.payoutTotalCents)
        assertEquals(30_000, closed.grossTotalCents, "bruto = diaria + entregas + km + payout")
    }

    @Test
    fun `G2 regression - km axis multiplies distance by tariff, not raw meters`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        // Caso A: perKm = R$2,00 (200), override 5.000 m (5 km) -> km = 5 x 200 = 1000.
        val driverA = newDriver("A")
        driverService.upsertConfig(
            driverA, actor,
            DriverConfigRequest(dailyRateCents = 0, perDeliveryCents = 0, perKmCents = 200),
        )
        val today = LocalDate.now(zone)
        val openedA = driverService.openSettlement(actor, OpenSettlementRequest(driverA, today, today))
        val closedA = driverService.closeSettlement(
            openedA.id, actor, CloseSettlementRequest(workingDays = 0, kmOverrideMeters = 5_000),
        )
        assertEquals(1_000, closedA.kmTotalCents, "5 km x R$2,00 = R$10,00")
        assertNotEquals(5_000, closedA.kmTotalCents, "NAO pode gravar os metros/centavos crus (bug G2)")

        // Caso B: perKm = R$0,50 (50), override 4.000 m (4 km) -> km = 4 x 50 = 200.
        val driverB = newDriver("B")
        driverService.upsertConfig(
            driverB, actor,
            DriverConfigRequest(dailyRateCents = 0, perDeliveryCents = 0, perKmCents = 50),
        )
        val openedB = driverService.openSettlement(actor, OpenSettlementRequest(driverB, today, today))
        val closedB = driverService.closeSettlement(
            openedB.id, actor, CloseSettlementRequest(workingDays = 0, kmOverrideMeters = 4_000),
        )
        assertEquals(200, closedB.kmTotalCents, "4 km x R$0,50 = R$2,00")
        assertNotEquals(4_000, closedB.kmTotalCents)
    }

    @Test
    fun `FROTA km comes from system distance when no override`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        val driverId = newDriver()
        // perKm = R$1,50 (150).
        driverService.upsertConfig(
            driverId, actor,
            DriverConfigRequest(dailyRateCents = 0, perDeliveryCents = 0, perKmCents = 150),
        )
        val now = Instant.now()
        // Entregas com distancia: 3.000 + 2.000 = 5.000 m (uma sem distancia -> ignorada).
        seedDelivered(driverId, OrderStatus.DELIVERED, now, distanceMeters = 3_000)
        seedDelivered(driverId, OrderStatus.DELIVERED, now, distanceMeters = 2_000)
        seedDelivered(driverId, OrderStatus.DELIVERED, now, distanceMeters = null)

        val today = LocalDate.now(zone)
        val opened = driverService.openSettlement(actor, OpenSettlementRequest(driverId, today, today))
        // Sem kmOverrideMeters: usa a soma do sistema (5.000 m = 5 km).
        val closed = driverService.closeSettlement(opened.id, actor, CloseSettlementRequest(workingDays = 0))

        assertEquals(5_000, closed.kmTotalMeters, "soma das distancias dos pedidos DELIVERED")
        assertEquals(750, closed.kmTotalCents, "5 km x R$1,50 = R$7,50")
    }

    @Test
    fun `FREELANCER - sums payout of accepted offers with delivered orders only`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        val driverId = newDriver("Free", driverType = "FREELANCER")
        // Config existe mas NAO deve ser aplicada no FREELANCER (3 eixos ficam zero).
        driverService.upsertConfig(
            driverId, actor,
            DriverConfigRequest(dailyRateCents = 9_999, perDeliveryCents = 9_999, perKmCents = 9_999),
        )
        val now = Instant.now()

        // 2 corridas validas (pedido DELIVERED) com payout R$8,00 e R$12,00.
        val o1 = seedDelivered(driverId, OrderStatus.DELIVERED, now)
        seedAcceptedOffer(driverId, o1, 800)
        val o2 = seedDelivered(driverId, OrderStatus.DELIVERED, now)
        seedAcceptedOffer(driverId, o2, 1_200)
        // Corrida com payout NULL (conta 0, mas offersWithoutPayout++) — pedido DELIVERED.
        val o3 = seedDelivered(driverId, OrderStatus.DELIVERED, now)
        seedAcceptedOffer(driverId, o3, null)
        // Corrida cujo pedido foi CANCELADO -> NAO conta (D-A), mesmo aceita.
        val o4 = seedDelivered(driverId, OrderStatus.CANCELLED, now)
        seedAcceptedOffer(driverId, o4, 5_000)

        val today = LocalDate.now(zone)
        val opened = driverService.openSettlement(actor, OpenSettlementRequest(driverId, today, today))
        val closed = driverService.closeSettlement(opened.id, actor, CloseSettlementRequest(workingDays = 7))

        assertEquals("FREELANCER", closed.settlementType)
        assertEquals(3, closed.deliveriesCount, "3 corridas DELIVERED (a cancelada nao conta)")
        assertEquals(2_000, closed.payoutTotalCents, "R$8,00 + R$12,00 + 0 (payout NULL)")
        assertEquals(1, closed.offersWithoutPayout, "uma corrida sem valor definido")
        // 3 eixos zerados (nao aplica config no freelancer).
        assertEquals(0, closed.dailyTotalCents)
        assertEquals(0, closed.deliveryTotalCents)
        assertEquals(0, closed.kmTotalCents)
        assertEquals(0, closed.workingDays)
        assertEquals(2_000, closed.grossTotalCents, "bruto = payout no freelancer")
    }

    @Test
    fun `snapshot - closed FREELANCER settlement unchanged after driver becomes FROTA`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        val driverId = newDriver("Free2", driverType = "FREELANCER")
        val now = Instant.now()
        val o1 = seedDelivered(driverId, OrderStatus.DELIVERED, now)
        seedAcceptedOffer(driverId, o1, 1_500)

        val today = LocalDate.now(zone)
        val opened = driverService.openSettlement(actor, OpenSettlementRequest(driverId, today, today))
        val closed = driverService.closeSettlement(opened.id, actor, CloseSettlementRequest(workingDays = 0))
        assertEquals("FREELANCER", closed.settlementType)
        assertEquals(1_500, closed.payoutTotalCents)

        // Muda o tipo do entregador para FROTA DEPOIS do fechamento.
        val driver = driverRepository.findById(driverId).get()
        driver.driverType = "FROTA"
        driverRepository.save(driver)

        // O acerto ja fechado permanece FREELANCER com os mesmos totais (snapshot).
        val reloaded = driverService.get(closed.id)
        assertEquals("FREELANCER", reloaded.settlementType)
        assertEquals(1_500, reloaded.payoutTotalCents)
        assertEquals(1_500, reloaded.grossTotalCents)
    }

    @Test
    fun `second open for the same driver is rejected`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        val driverId = newDriver()
        val today = LocalDate.now(zone)
        driverService.openSettlement(actor, OpenSettlementRequest(driverId, today, today))

        assertThrows(ConflictException::class.java) {
            driverService.openSettlement(actor, OpenSettlementRequest(driverId, today, today))
        }
    }

    @Test
    fun `closing FROTA without remuneration config is rejected`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        val driverId = newDriver()
        val today = LocalDate.now(zone)
        val opened = driverService.openSettlement(actor, OpenSettlementRequest(driverId, today, today))

        assertThrows(BusinessException::class.java) {
            driverService.closeSettlement(opened.id, actor, CloseSettlementRequest(workingDays = 1))
        }
    }
}

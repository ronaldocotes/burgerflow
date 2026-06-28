package com.menuflow

import com.menuflow.dto.CloseSettlementRequest
import com.menuflow.dto.DriverConfigRequest
import com.menuflow.dto.OpenSettlementRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.Order
import com.menuflow.model.OrderStatus
import com.menuflow.repository.tenant.DeliveryDriverRepository
import com.menuflow.repository.tenant.OrderRepository
import com.menuflow.service.DriverService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Acerto financeiro de entregadores (Fase 2.5) contra um Postgres real
 * (Testcontainers). Prova:
 *  - fluxo completo: configurar remuneracao -> abrir -> fechar com 10 entregas ->
 *    totais e bruto corretos (centavos), so contando entregas DELIVERED do
 *    periodo e do entregador certo;
 *  - segundo acerto OPEN do mesmo entregador -> 409;
 *  - fechar sem config de remuneracao -> 400.
 *
 * Cada caso usa seu PROPRIO tenant (db isolado). Nao e @Transactional: cada save
 * commita, entao open() enxerga as entregas semeadas antes do close().
 */
class DriverSettlementTest @Autowired constructor(
    private val driverService: DriverService,
    private val driverRepository: DeliveryDriverRepository,
    private val orderRepository: OrderRepository,
) : IntegrationTestBase() {

    private val zone = ZoneId.of("America/Sao_Paulo")

    @AfterEach
    fun clear() = TenantContext.clear()

    private fun newDriver(name: String = "Entregador", active: Boolean = true): UUID {
        val saved = driverRepository.save(
            DeliveryDriver(name = name, phone = "11999999999", active = active, tenantId = UUID.randomUUID()),
        )
        return saved.id!!
    }

    @Test
    fun `listDrivers - returns all drivers ordered by name with active flag`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        TenantContext.set(tenant)

        // Inseridos fora de ordem alfabetica; um inativo.
        val carlosId = newDriver("Carlos", active = true)
        val anaId = newDriver("Ana", active = false)
        val brunoId = newDriver("Bruno", active = true)

        val drivers = driverService.listDrivers()

        assertEquals(3, drivers.size)
        // Ordenado por nome (Ana, Bruno, Carlos).
        assertEquals(listOf("Ana", "Bruno", "Carlos"), drivers.map { it.name })
        // id e o DeliveryDriver.id (driverId do acerto), nao usuario de controle.
        assertEquals(listOf(anaId, brunoId, carlosId), drivers.map { it.id })
        // Inclui inativos com a flag correta.
        assertEquals(false, drivers.first { it.id == anaId }.isActive)
        assertEquals(true, drivers.first { it.id == carlosId }.isActive)
    }

    /** Persiste um pedido DELIVERED carimbado com o entregador, completado em [completedAt]. */
    private fun seedDelivered(driverId: UUID?, status: OrderStatus, completedAt: Instant?) {
        orderRepository.save(
            Order(
                orderNumber = "D-${UUID.randomUUID().toString().take(10)}",
                status = status,
                subtotalCents = 1000,
                totalCents = 1000,
                driverId = driverId,
                completedAt = completedAt,
            ),
        )
    }

    @Test
    fun `full flow - configure, open, close with 10 deliveries, correct totals`() {
        val tenant = "drv_${UUID.randomUUID().toString().take(8)}"
        val actor = UUID.randomUUID()
        TenantContext.set(tenant)

        val driverId = newDriver()
        val otherDriver = newDriver("Outro")

        // Remuneracao: diaria R$50,00 + R$3,00 por entrega.
        driverService.upsertConfig(
            driverId,
            actor,
            DriverConfigRequest(dailyRateCents = 5_000, perDeliveryCents = 300, perKmCents = 0),
        )

        val now = Instant.now()
        // 10 entregas validas: DELIVERED, deste entregador, dentro do periodo (hoje).
        repeat(10) { seedDelivered(driverId, OrderStatus.DELIVERED, now) }
        // Ruido que NAO pode ser contado:
        seedDelivered(driverId, OrderStatus.PREPARING, now)                       // nao DELIVERED
        seedDelivered(otherDriver, OrderStatus.DELIVERED, now)                    // outro entregador
        seedDelivered(driverId, OrderStatus.DELIVERED, now.minus(5, ChronoUnit.DAYS)) // fora do periodo

        val today = LocalDate.now(zone)
        val opened = driverService.openSettlement(
            actor,
            OpenSettlementRequest(driverId = driverId, periodStart = today, periodEnd = today),
        )
        assertEquals("OPEN", opened.status)

        // Fecha: 5 dias trabalhados + km informado R$12,34.
        val closed = driverService.closeSettlement(
            opened.id,
            actor,
            CloseSettlementRequest(workingDays = 5, kmTotalCents = 1_234),
        )

        assertEquals("CLOSED", closed.status)
        assertEquals(10, closed.deliveriesCount, "so as 10 entregas DELIVERED deste entregador no periodo")
        assertEquals(5, closed.workingDays)
        assertEquals(3_000, closed.deliveryTotalCents, "10 entregas x R$3,00")
        assertEquals(25_000, closed.dailyTotalCents, "5 dias x R$50,00")
        assertEquals(1_234, closed.kmTotalCents)
        assertEquals(29_234, closed.grossTotalCents, "bruto = diaria + entregas + km")
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
    fun `closing without remuneration config is rejected`() {
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

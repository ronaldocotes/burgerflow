package com.menuflow

import com.menuflow.dto.AvailabilityRequest
import com.menuflow.dto.ProductCreateRequest
import com.menuflow.dto.WindowDto
import com.menuflow.exception.BusinessException
import com.menuflow.service.ProductAvailabilityService
import com.menuflow.service.ProductService
import com.menuflow.tenant.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Disponibilidade por canal (4 canais) e por janela de horário. */
class ProductAvailabilityTest @Autowired constructor(
    private val service: ProductAvailabilityService,
    private val productService: ProductService,
) : IntegrationTestBase() {

    @AfterEach
    fun clear() = TenantContext.clear()

    private val sp = ZoneId.of("America/Sao_Paulo")

    private fun newProduct() = productService.create(
        ProductCreateRequest(
            categoryId = UUID.randomUUID(),
            sku = "AV-${UUID.randomUUID().toString().take(6)}",
            name = "Produto",
            priceCents = 1000,
        ),
    ).id

    @Test
    fun `no config means available in any channel and time`() {
        TenantContext.set("av1")
        val p = newProduct()
        assertTrue(service.isAvailableNow(p, "DELIVERY"))
        assertTrue(service.isAvailableNow(p, null))
    }

    @Test
    fun `channel restriction limits availability`() {
        TenantContext.set("av2")
        val p = newProduct()
        service.set(p, AvailabilityRequest(channels = listOf("DELIVERY", "ONLINE")))
        assertTrue(service.isAvailableNow(p, "DELIVERY"))
        assertFalse(service.isAvailableNow(p, "COUNTER"), "produto não vendido no balcão")
    }

    @Test
    fun `put availability is idempotent and replaces previous rows`() {
        TenantContext.set("av_replace")
        val p = newProduct()

        service.set(
            p,
            AvailabilityRequest(
                channels = listOf("DELIVERY", "ONLINE"),
                windows = listOf(WindowDto(1, 480, 1320)),
            ),
        )

        val replaced = service.set(
            p,
            AvailabilityRequest(
                channels = listOf("DELIVERY", "ONLINE"),
                windows = listOf(WindowDto(2, 600, 1200)),
            ),
        )

        assertTrue(replaced.channels.containsAll(listOf("DELIVERY", "ONLINE")))
        assertTrue(replaced.windows == listOf(WindowDto(2, 600, 1200)))
        assertTrue(service.isAvailableNow(p, "DELIVERY", ZonedDateTime.of(2026, 6, 30, 11, 0, 0, 0, sp)))
        assertFalse(service.isAvailableNow(p, "DELIVERY", ZonedDateTime.of(2026, 6, 29, 11, 0, 0, 0, sp)))
    }

    @Test
    fun `time window restricts by day of week and hour`() {
        TenantContext.set("av3")
        val p = newProduct()
        // Segunda (dow=1), 08:00–22:00 -> minutos 480..1320.
        service.set(p, AvailabilityRequest(windows = listOf(WindowDto(1, 480, 1320))))
        val mondayMidday = ZonedDateTime.of(2026, 6, 29, 10, 0, 0, 0, sp) // 2026-06-29 = segunda
        val mondayNight = ZonedDateTime.of(2026, 6, 29, 23, 0, 0, 0, sp)
        val sundayMidday = ZonedDateTime.of(2026, 6, 28, 10, 0, 0, 0, sp) // domingo
        assertTrue(service.isAvailableNow(p, null, mondayMidday))
        assertFalse(service.isAvailableNow(p, null, mondayNight), "fora do horário")
        assertFalse(service.isAvailableNow(p, null, sundayMidday), "dia sem janela")
    }

    @Test
    fun `invalid channel is rejected`() {
        TenantContext.set("av4")
        val p = newProduct()
        assertThrows<BusinessException> { service.set(p, AvailabilityRequest(channels = listOf("PNEU"))) }
    }
}

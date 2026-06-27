package com.menuflow.service

import com.menuflow.dto.AvailabilityRequest
import com.menuflow.dto.AvailabilityResponse
import com.menuflow.dto.WindowDto
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.ProductAvailabilityWindow
import com.menuflow.model.ProductChannel
import com.menuflow.model.SalesChannel
import com.menuflow.repository.tenant.ProductAvailabilityWindowRepository
import com.menuflow.repository.tenant.ProductChannelRepository
import com.menuflow.repository.tenant.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Disponibilidade do produto por canal de venda e janelas de horário.
 * Sem canais cadastrados = disponível em todos; sem janelas = qualquer horário.
 */
@Service
class ProductAvailabilityService(
    private val productRepository: ProductRepository,
    private val channelRepo: ProductChannelRepository,
    private val windowRepo: ProductAvailabilityWindowRepository,
) {
    private val saoPaulo = ZoneId.of("America/Sao_Paulo")

    @Transactional("tenantTransactionManager", readOnly = true)
    fun get(productId: UUID): AvailabilityResponse {
        ensureProduct(productId)
        return build(productId)
    }

    /** Substitui o conjunto de canais e janelas do produto (PUT idempotente). */
    @Transactional("tenantTransactionManager")
    fun set(productId: UUID, req: AvailabilityRequest): AvailabilityResponse {
        ensureProduct(productId)
        val channels = req.channels.map { parseChannel(it) }.toSet()
        req.windows.forEach {
            require(it.startMinute < it.endMinute) { "Janela inválida: início deve ser antes do fim" }
        }
        channelRepo.deleteByProductId(productId)
        windowRepo.deleteByProductId(productId)
        channels.forEach { channelRepo.save(ProductChannel(productId = productId, channel = it)) }
        req.windows.forEach {
            windowRepo.save(
                ProductAvailabilityWindow(
                    productId = productId, dayOfWeek = it.dayOfWeek,
                    startMinute = it.startMinute, endMinute = it.endMinute,
                ),
            )
        }
        return build(productId)
    }

    /** Produto disponível AGORA (opcionalmente num canal)? Sem config = disponível. */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun isAvailableNow(
        productId: UUID,
        channelRaw: String? = null,
        nowParam: ZonedDateTime? = null,
    ): Boolean {
        ensureProduct(productId)
        // Resolve no CORPO (não no default): o default referenciaria o campo saoPaulo
        // no contexto do proxy CGLIB de @Transactional, onde ele vem null.
        val now = nowParam ?: ZonedDateTime.now(saoPaulo)
        val channels = channelRepo.findByProductId(productId).map { it.channel }.toSet()
        val channelOk = channels.isEmpty() || channelRaw == null || parseChannel(channelRaw) in channels
        val windows = windowRepo.findByProductId(productId)
        if (windows.isEmpty()) return channelOk
        val dow = now.dayOfWeek.value
        val minute = now.hour * 60 + now.minute
        val timeOk = windows.any { it.dayOfWeek == dow && minute >= it.startMinute && minute < it.endMinute }
        return channelOk && timeOk
    }

    private fun build(productId: UUID): AvailabilityResponse {
        val channels = channelRepo.findByProductId(productId).map { it.channel.name }.sorted()
        val windows = windowRepo.findByProductId(productId)
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startMinute }))
            .map { WindowDto(it.dayOfWeek, it.startMinute, it.endMinute) }
        return AvailabilityResponse(productId, channels, windows)
    }

    private fun ensureProduct(productId: UUID) {
        productRepository.findById(productId)
            .orElseThrow { ResourceNotFoundException("Produto não encontrado: $productId") }
    }

    private fun parseChannel(raw: String): SalesChannel =
        runCatching { SalesChannel.valueOf(raw.uppercase()) }
            .getOrElse { throw BusinessException("Canal inválido: $raw") }
}

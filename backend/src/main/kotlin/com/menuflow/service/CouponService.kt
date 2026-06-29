package com.menuflow.service

import com.menuflow.dto.CouponCreateRequest
import com.menuflow.dto.CouponRedemptionResponse
import com.menuflow.dto.CouponResponse
import com.menuflow.dto.CouponUpdateRequest
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.Coupon
import com.menuflow.model.CouponRedemption
import com.menuflow.model.DiscountType
import com.menuflow.repository.tenant.CouponRedemptionRepository
import com.menuflow.repository.tenant.CouponRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/**
 * Cupons & Descontos (Fase 3.2). Tudo no banco do TENANT (db-per-tenant). Dinheiro
 * SEMPRE em centavos.
 *
 * Validação + aplicação no pedido: [validateAndLock] trava a linha do cupom (lock
 * pessimista) para serializar redenções concorrentes e evitar estourar o maxUses em
 * corrida; a redenção é registrada por [recordRedemption] DENTRO da mesma transação
 * do create do pedido (atômico — se o pedido reverte, a redenção reverte junto).
 *
 * O [preview] é a pré-checagem pública (sem lock, sem registrar): só diz se o cupom
 * vale e quanto desconta, para o cliente ver antes de fechar o carrinho.
 */
@Service
class CouponService(
    private val couponRepository: CouponRepository,
    private val redemptionRepository: CouponRedemptionRepository,
    private val auditLogService: AuditLogService,
) {

    // ----------------------------- CRUD (ADMIN/MANAGER) -----------------------------

    @Transactional("tenantTransactionManager")
    fun create(req: CouponCreateRequest, actorId: UUID?): CouponResponse {
        val code = normalizeCode(req.code)
        if (couponRepository.findByCode(code) != null) {
            throw BusinessException("Já existe um cupom com o código '$code'")
        }
        validateDefinition(req.discountType, req.discountValue, req.validFrom, req.validUntil)
        val saved = couponRepository.save(
            Coupon(
                code = code,
                description = req.description?.trim()?.ifBlank { null },
                discountType = req.discountType,
                discountValue = req.discountValue,
                minOrderCents = req.minOrderCents,
                maxUses = req.maxUses,
                maxUsesPerCustomer = req.maxUsesPerCustomer,
                validFrom = req.validFrom,
                validUntil = req.validUntil,
                active = req.active,
            ),
        )
        auditLogService.log(
            action = "coupon.create",
            entity = "coupon",
            entityId = saved.id,
            after = mapOf("code" to saved.code, "type" to saved.discountType.name, "value" to saved.discountValue),
            actorUserId = actorId,
        )
        return CouponResponse.from(saved)
    }

    @Transactional("tenantTransactionManager")
    fun update(id: UUID, req: CouponUpdateRequest, actorId: UUID?): CouponResponse {
        val coupon = load(id)
        validateDefinition(req.discountType, req.discountValue, req.validFrom, req.validUntil)
        coupon.description = req.description?.trim()?.ifBlank { null }
        coupon.discountType = req.discountType
        coupon.discountValue = req.discountValue
        coupon.minOrderCents = req.minOrderCents
        coupon.maxUses = req.maxUses
        coupon.maxUsesPerCustomer = req.maxUsesPerCustomer
        coupon.validFrom = req.validFrom
        coupon.validUntil = req.validUntil
        coupon.active = req.active
        val saved = couponRepository.save(coupon)
        auditLogService.log(
            action = "coupon.update",
            entity = "coupon",
            entityId = saved.id,
            after = mapOf("code" to saved.code, "type" to saved.discountType.name, "value" to saved.discountValue, "active" to saved.active),
            actorUserId = actorId,
        )
        return CouponResponse.from(saved)
    }

    /** Soft-delete: apenas desativa (active=false); preserva histórico de redenções. */
    @Transactional("tenantTransactionManager")
    fun deactivate(id: UUID, actorId: UUID?) {
        val coupon = load(id)
        coupon.active = false
        couponRepository.save(coupon)
        auditLogService.log(
            action = "coupon.deactivate",
            entity = "coupon",
            entityId = coupon.id,
            actorUserId = actorId,
        )
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun list(active: Boolean?, pageable: Pageable): Page<CouponResponse> {
        val page = if (active == null) couponRepository.findAll(pageable)
        else couponRepository.findByActive(active, pageable)
        return page.map { CouponResponse.from(it) }
    }

    @Transactional("tenantTransactionManager", readOnly = true)
    fun listRedemptions(couponId: UUID, pageable: Pageable): Page<CouponRedemptionResponse> {
        if (!couponRepository.existsById(couponId)) throw ResourceNotFoundException("Cupom não encontrado")
        return redemptionRepository.findByCouponIdOrderByRedeemedAtDesc(couponId, pageable)
            .map { CouponRedemptionResponse.from(it) }
    }

    // ----------------------------- Preview (público) -----------------------------

    /**
     * Pré-checagem pública: valida o cupom contra o subtotal e o telefone, sem travar
     * nem registrar. Lança exceção descritiva (404/400) quando inválido.
     */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun preview(rawCode: String, subtotalCents: Long, customerPhone: String?): CouponApplication {
        val coupon = couponRepository.findByCode(normalizeCode(rawCode))
            ?: throw ResourceNotFoundException("Cupom não encontrado")
        return validateAndCompute(coupon, subtotalCents, customerPhone)
    }

    // ----------------------------- Aplicação no pedido -----------------------------

    /**
     * Valida o cupom travando a linha (lock pessimista) para o create do pedido. Deve
     * ser chamado DENTRO da transação do create (propagação REQUIRED -> junta na tx do
     * pedido) para que o lock segure até o commit. Lança 404/400 quando inválido.
     */
    @Transactional("tenantTransactionManager")
    fun validateAndLock(rawCode: String, subtotalCents: Long, customerPhone: String?): CouponApplication {
        val coupon = couponRepository.findByCodeForUpdate(normalizeCode(rawCode))
            ?: throw ResourceNotFoundException("Cupom não encontrado")
        return validateAndCompute(coupon, subtotalCents, customerPhone)
    }

    /** Registra a redenção do cupom (na mesma tx do create, após o pedido existir). */
    @Transactional("tenantTransactionManager")
    fun recordRedemption(coupon: Coupon, orderId: UUID, customerPhone: String?, discountApplied: Long) {
        redemptionRepository.save(
            CouponRedemption(
                couponId = coupon.id!!,
                orderId = orderId,
                customerPhone = normalizePhone(customerPhone),
                discountAppliedCents = discountApplied,
            ),
        )
    }

    // ----------------------------- Núcleo -----------------------------

    private fun validateAndCompute(coupon: Coupon, subtotalCents: Long, customerPhone: String?): CouponApplication {
        if (!coupon.active) throw BusinessException("Cupom inválido")
        val now = Instant.now()
        if (now.isBefore(coupon.validFrom) || now.isAfter(coupon.validUntil)) {
            throw BusinessException("Cupom expirado")
        }
        if (subtotalCents < coupon.minOrderCents) {
            throw BusinessException("Pedido mínimo ${formatBRL(coupon.minOrderCents)} para usar este cupom")
        }
        // Limite global de usos.
        coupon.maxUses?.let { max ->
            if (redemptionRepository.countByCouponId(coupon.id!!) >= max) {
                throw BusinessException("Cupom esgotado")
            }
        }
        // Limite por cliente (só verificável quando há telefone; uso anônimo não conta
        // para o teto por cliente — documentado).
        val phone = normalizePhone(customerPhone)
        if (phone != null) {
            if (redemptionRepository.countByCouponIdAndCustomerPhone(coupon.id!!, phone) >= coupon.maxUsesPerCustomer) {
                throw BusinessException("Cupom já utilizado")
            }
        }
        return CouponApplication(coupon, computeDiscount(coupon, subtotalCents), describe(coupon))
    }

    /**
     * Desconto em centavos. FIXED nunca ultrapassa o subtotal; PERCENT arredonda
     * HALF-UP (discountValue 1500 = 15%) e também é limitado ao subtotal.
     */
    fun computeDiscount(coupon: Coupon, subtotalCents: Long): Long = when (coupon.discountType) {
        DiscountType.FIXED -> minOf(coupon.discountValue, subtotalCents).coerceAtLeast(0)
        DiscountType.PERCENT -> {
            val raw = BigDecimal.valueOf(subtotalCents)
                .multiply(BigDecimal.valueOf(coupon.discountValue))
                .divide(BigDecimal(10_000), 0, RoundingMode.HALF_UP)
                .toLong()
            minOf(raw, subtotalCents).coerceAtLeast(0)
        }
    }

    // ----------------------------- Helpers -----------------------------

    private fun load(id: UUID): Coupon =
        couponRepository.findById(id).orElseThrow { ResourceNotFoundException("Cupom não encontrado") }

    private fun normalizeCode(raw: String): String = raw.trim().uppercase()

    private fun normalizePhone(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    private fun validateDefinition(type: DiscountType, value: Long, from: Instant, until: Instant) {
        if (value <= 0) throw BusinessException("Valor do desconto deve ser positivo")
        if (type == DiscountType.PERCENT && value > 10_000) {
            throw BusinessException("Desconto percentual não pode exceder 100% (valor 10000)")
        }
        if (!from.isBefore(until)) throw BusinessException("Início da validade deve ser anterior ao fim")
    }

    private fun describe(coupon: Coupon): String? = coupon.description ?: when (coupon.discountType) {
        DiscountType.FIXED -> "${formatBRL(coupon.discountValue)} de desconto"
        DiscountType.PERCENT -> "${formatPct(coupon.discountValue)} de desconto"
    }

    private fun formatBRL(cents: Long): String = "R$ %d,%02d".format(cents / 100, cents % 100)

    private fun formatPct(valueX100: Long): String =
        if (valueX100 % 100 == 0L) "${valueX100 / 100}%"
        else "%d,%02d%%".format(valueX100 / 100, valueX100 % 100)
}

/** Resultado da validação de um cupom: a entidade, o desconto e o rótulo legível. */
data class CouponApplication(
    val coupon: com.menuflow.model.Coupon,
    val discountCents: Long,
    val description: String?,
)

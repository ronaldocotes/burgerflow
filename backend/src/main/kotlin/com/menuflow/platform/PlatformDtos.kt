package com.menuflow.platform

import com.menuflow.model.control.RestaurantType
import com.menuflow.model.control.SubscriptionPlan
import com.menuflow.model.control.Tenant
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Regex do slug: minúsculas, dígitos e hífen, 3–30 chars. CRÍTICO: o slug vira
 * identificador de banco em CREATE DATABASE "tenant_<slug>" — injeção aqui é RCE de
 * SQL. Validado no DTO (Bean Validation) E de novo no service (fail-closed).
 */
const val SLUG_REGEX = "^[a-z0-9-]{3,30}$"

// ── Requests ────────────────────────────────────────────────────────────────

data class CreateTenantRequest(
    @field:NotBlank
    @field:Pattern(regexp = SLUG_REGEX, message = "slug inválido: use minúsculas, dígitos e hífen (3–30)")
    val slug: String,

    @field:NotBlank
    @field:Size(max = 120)
    val displayName: String,

    val plan: SubscriptionPlan = SubscriptionPlan.BASIC,

    val restaurantType: RestaurantType = RestaurantType.HAMBURGUERIA,

    /** E-mail do primeiro admin do tenant. Recebe um convite (link), não senha. */
    @field:NotBlank
    @field:Size(max = 255)
    val adminEmail: String,
)

/** Ativa/desativa e/ou troca o plano. Campos nulos = não alterar (PATCH parcial). */
data class UpdateTenantRequest(
    val isActive: Boolean? = null,
    val plan: SubscriptionPlan? = null,
)

/** Toggle otimista de um módulo. */
data class ToggleModuleRequest(
    val enabled: Boolean,
)

// ── Responses ───────────────────────────────────────────────────────────────

data class TenantSummaryResponse(
    val slug: String,
    val displayName: String,
    val plan: SubscriptionPlan,
    val restaurantType: RestaurantType,
    val isActive: Boolean,
    val createdAt: Instant,
    val expiresAt: Instant?,
) {
    companion object {
        fun from(t: Tenant) = TenantSummaryResponse(
            slug = t.slug,
            displayName = t.displayName,
            plan = t.subscriptionPlan,
            restaurantType = t.restaurantType,
            isActive = t.isActive,
            createdAt = t.createdAt,
            expiresAt = t.expiresAt,
        )
    }
}

/**
 * Devolvido na criação. O inviteLink carrega o token CRU do convite do admin — só
 * aparece UMA vez aqui; o banco guarda apenas o hash. Nunca logar o link inteiro.
 */
data class TenantCreatedResponse(
    val slug: String,
    val displayName: String,
    val plan: SubscriptionPlan,
    val isActive: Boolean,
    val adminEmail: String,
    val inviteLink: String,
)

/**
 * Status efetivo de um módulo para um tenant: [enabled] é o valor em vigor;
 * [source] indica se veio de um OVERRIDE (linha em tenant_module) ou do DEFAULT do
 * plano. A UI usa isso para mostrar "herdado do plano" vs "definido manualmente".
 */
data class ModuleStatusResponse(
    val moduleKey: String,
    val label: String,
    val enabled: Boolean,
    val source: String,
) {
    enum class Source { OVERRIDE, PLAN_DEFAULT }
}

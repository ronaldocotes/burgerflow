package com.menuflow.platform

import com.menuflow.model.control.RestaurantType
import com.menuflow.model.control.SubscriptionPlan
import com.menuflow.model.control.Tenant
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Email
import com.menuflow.model.control.IfoodAppRole
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Regex do slug: minúsculas, dígitos e hífen, 3–30 chars. CRÍTICO: o slug vira
 * identificador de banco em CREATE DATABASE "tenant_<slug>" — injeção aqui é RCE de
 * SQL. Validado no DTO (Bean Validation) E de novo no service (fail-closed).
 */
const val SLUG_REGEX = "^[a-z0-9]{3,30}$"

// ── Requests ────────────────────────────────────────────────────────────────

data class CreateTenantRequest(
    @field:NotBlank
    @field:Pattern(regexp = SLUG_REGEX, message = "slug inválido: use apenas letras minúsculas e dígitos (3–30)")
    val slug: String,

    @field:NotBlank
    @field:Size(max = 120)
    val displayName: String,

    val plan: SubscriptionPlan = SubscriptionPlan.BASIC,

    val restaurantType: RestaurantType = RestaurantType.HAMBURGUERIA,

    /** E-mail do primeiro admin do tenant. Recebe um convite (link), não senha. */
    @field:Email
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

// ── F2: Integrations Health ──────────────────────────────────────────────────

enum class IntegrationStatus { OK, DEGRADED, DOWN }

data class IntegrationCard(
    val name: String,
    val status: IntegrationStatus,
    val detail: String? = null,
)

data class IntegrationsHealthResponse(
    val updatedAt: Instant,
    val cards: List<IntegrationCard>,
)

// ── F2: Tenant Usage Snapshot ────────────────────────────────────────────────

data class TenantUsageResponse(
    val ordersThisMonth: Long,
    val dbSizeMb: Long,
    val lastLoginAt: Instant?,
    val snapshotDate: LocalDate?,
)

// ── F2: iFood App Config (escrita protegida — clientSecret nunca devolvido) ──

data class IfoodAppSummaryResponse(
    val id: UUID,
    val role: IfoodAppRole,
    val clientId: String,
    /** Últimos 4 caracteres do clientSecret (decifrado no servidor, nunca o valor completo). */
    val secretLast4: String,
    val cnpj: String,
    val active: Boolean,
    val createdAt: Instant,
)

data class CreateIfoodAppRequest(
    val role: IfoodAppRole = IfoodAppRole.PRIMARY,
    @field:jakarta.validation.constraints.NotBlank val clientId: String,
    /** Texto claro — cifrado em AES-256-GCM antes de persistir. NUNCA retornado. */
    @field:jakarta.validation.constraints.NotBlank val clientSecret: String,
    @field:jakarta.validation.constraints.NotBlank val cnpj: String,
    val active: Boolean = false,
)

data class RotateIfoodSecretRequest(
    /** Novo clientSecret em texto claro — cifrado imediatamente, keyVersion incrementado. */
    @field:jakarta.validation.constraints.NotBlank val clientSecret: String,
)

// ── F3: AI Usage (painel de consumo de IA por tenant) ───────────────────────

/**
 * Consumo de IA de UM tenant num mes. Os campos inputTokens/outputTokens mapeiam
 * para promptTokens/completionTokens da entidade AiUsage (terminologia OpenAI/Anthropic).
 * Nota: a entidade agrega por (tenant, mes) — nao ha campo de modelo, o campo
 * foi omitido do DTO de forma intencional para nao inventar dados ausentes.
 */
data class AiUsageEntry(
    val tenantSlug: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsdMicros: Long,
    val callCount: Long,
)

data class AiUsageResponse(
    val month: String,
    val entries: List<AiUsageEntry>,
    val totalCostUsdMicros: Long,
    val totalCalls: Long,
)

// ── F3: Platform Users (gestao de SUPER_ADMINs) ──────────────────────────────

/**
 * Resumo de um SUPER_ADMIN para o painel de plataforma.
 * tenantSlug indica em qual hamburgueria o usuario foi criado (SUPER_ADMIN e
 * um papel num tenant real, nao num tenant especial de plataforma).
 *
 * has2FA: true se o usuario completou o setup do TOTP.
 * NOTA DE PRODUCAO: requer V15 migration (ALTER TABLE users ADD COLUMN totp_secret)
 * para persistencia do segredo entre reinicializacoes. Enquanto isso, o valor e
 * derivado do cache em memoria do TotpService.
 */
data class PlatformUserSummary(
    val id: UUID,
    val name: String,
    val email: String,
    val tenantSlug: String,
    val createdAt: Instant,
    val lastLoginAt: Instant?,
    val has2FA: Boolean,
)

/** Convite para um novo SUPER_ADMIN. O convite e criado no tenant do solicitante. */
data class InvitePlatformUserRequest(
    @field:jakarta.validation.constraints.Email
    @field:jakarta.validation.constraints.NotBlank
    @field:jakarta.validation.constraints.Size(max = 255)
    val email: String,
)

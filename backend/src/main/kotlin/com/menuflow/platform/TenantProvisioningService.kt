package com.menuflow.platform

import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.control.InvitationStatus
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.UserInvitation
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserInvitationRepository
import com.menuflow.security.SecurityUtils
import com.menuflow.tenant.DynamicTenantRoutingDataSource
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Provisionamento de tenants pelo painel super-admin. Roda no banco de CONTROLE
 * (Tenant + convite do admin). O banco FÍSICO do tenant e o schema (Flyway) nascem
 * ao tocar o datasource roteado — mesmo mecanismo lazy usado pelo DevDataSeeder.
 *
 * SEGURANÇA (defesa em profundidade sobre o gate SUPER_ADMIN):
 *  - slug validado contra SLUG_REGEX no service TAMBÉM (não confia só no DTO): o
 *    slug vira nome de banco em CREATE DATABASE "tenant_<slug>" — injeção = RCE SQL.
 *  - unicidade de slug => 409 (o índice unique em tenants.slug é o cinto final).
 * IDEMPOTÊNCIA: slug já existente => 409 (contrato explícito); o CREATE DATABASE é
 * checado antes (pg_database) pelo routing datasource, então reprovisionar é seguro.
 */
@Service
class TenantProvisioningService(
    private val tenantRepository: TenantRepository,
    private val invitationRepository: UserInvitationRepository,
    @Qualifier("tenantRoutingDataSource")
    private val routingDataSource: DynamicTenantRoutingDataSource,
    private val moduleGateService: ModuleGateService,
    private val auditService: PlatformAuditService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional("controlTransactionManager", readOnly = true)
    fun listTenants(): List<TenantSummaryResponse> =
        tenantRepository.findAll().map { TenantSummaryResponse.from(it) }

    @Transactional("controlTransactionManager")
    fun provision(req: CreateTenantRequest): TenantCreatedResponse {
        val slug = req.slug.trim().lowercase()
        // Fail-closed: revalida o slug mesmo já validado no DTO.
        require(SLUG_REGEX.toRegex().matches(slug)) { "slug inválido" }

        if (tenantRepository.existsBySlug(slug)) {
            throw ConflictException("Já existe um tenant com o slug '$slug'")
        }

        val tenant = tenantRepository.save(
            Tenant(
                slug = slug,
                displayName = req.displayName.trim(),
                subscriptionPlan = req.plan,
                restaurantType = req.restaurantType,
                isActive = true,
            ),
        )

        // Materializa o banco físico tenant_<slug> + Flyway ao primeiro acesso.
        provisionDatabase(slug)

        // Admin do tenant nasce por CONVITE (link com token cru, só aqui). O banco
        // guarda apenas o hash SHA-256; expira em 72h.
        val principal = SecurityUtils.currentPrincipalOrThrow()
        val adminEmail = req.adminEmail.trim().lowercase()
        val rawToken = UUID.randomUUID().toString()
        invitationRepository.save(
            UserInvitation(
                tenantId = tenant.id!!,
                email = adminEmail,
                tokenHash = sha256(rawToken),
                invitedByUserId = principal.userId,
                expiresAt = Instant.now().plus(72, ChronoUnit.HOURS),
                role = UserRole.ADMIN,
                status = InvitationStatus.PENDING,
            ),
        )

        auditService.record(
            action = "TENANT_CREATE",
            targetTenantId = tenant.id,
            targetEntity = "tenant",
            after = mapOf(
                "slug" to slug,
                "plan" to req.plan.name,
                "adminEmail" to adminEmail,
            ),
        )

        return TenantCreatedResponse(
            slug = tenant.slug,
            displayName = tenant.displayName,
            plan = tenant.subscriptionPlan,
            isActive = tenant.isActive,
            adminEmail = adminEmail,
            inviteLink = "/aceitar-convite?token=$rawToken",
        )
    }

    @Transactional("controlTransactionManager")
    fun updateTenant(slug: String, req: UpdateTenantRequest): TenantSummaryResponse {
        val tenant = tenantRepository.findBySlug(slug)
            ?: throw ResourceNotFoundException("Tenant não encontrado: $slug")
        val before = mapOf("isActive" to tenant.isActive, "plan" to tenant.subscriptionPlan.name)

        req.isActive?.let { tenant.isActive = it }
        req.plan?.let { tenant.subscriptionPlan = it }
        tenantRepository.save(tenant)

        // Troca de plano muda os defaults de módulo => invalida o cache do gate.
        // Desativar tenant precisa refletir logo no acesso: o cache do JwtAuthFilter
        // tem TTL próprio (60s) e converge sozinho.
        moduleGateService.invalidate(tenant.id!!)

        val action = when {
            req.isActive == false -> "TENANT_DEACTIVATE"
            req.isActive == true -> "TENANT_ACTIVATE"
            else -> "TENANT_UPDATE"
        }
        auditService.record(
            action = action,
            targetTenantId = tenant.id,
            targetEntity = "tenant",
            before = before,
            after = mapOf("isActive" to tenant.isActive, "plan" to tenant.subscriptionPlan.name),
        )
        return TenantSummaryResponse.from(tenant)
    }

    /**
     * Liga o TenantContext e abre UMA conexão pelo datasource roteado; isso dispara,
     * no primeiro acesso, o CREATE DATABASE (checado por pg_database) e o Flyway do
     * schema do tenant (idempotente). Restaura o contexto anterior em finally.
     */
    private fun provisionDatabase(slug: String) {
        val previous = TenantContext.get()
        TenantContext.set(slug)
        try {
            routingDataSource.connection.use { conn ->
                // Validar a conexão força a criação do pool + schemaInitializer (Flyway).
                conn.isValid(5)
            }
            log.info("[PROVISION] Banco do tenant '{}' provisionado e migrado", slug)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

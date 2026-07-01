package com.menuflow.service

import com.menuflow.dto.AcceptInviteRequest
import com.menuflow.dto.ActivateRequest
import com.menuflow.dto.ChangeRoleRequest
import com.menuflow.dto.InvitationResponse
import com.menuflow.dto.InviteRequest
import com.menuflow.dto.InviteResponse
import com.menuflow.dto.RevokeResponse
import com.menuflow.dto.TokenResponse
import com.menuflow.dto.UserResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.exception.UnauthorizedException
import com.menuflow.model.control.InvitationStatus
import com.menuflow.model.control.User
import com.menuflow.model.control.UserInvitation
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserInvitationRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.security.AuthPrincipal
import com.menuflow.security.SecurityUtils
import com.menuflow.tenant.TenantContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Módulo de usuários/convites no banco de CONTROLE. TODA operação é escopada pelo
 * tenant do principal (principal.tenantUuid) — re-autorização por objeto: o usuário/
 * convite alvo TEM que pertencer ao mesmo tenant (anti-IDOR cross-tenant).
 *
 * Auditoria: cada mutação chama auditLogService.log() ligando o TenantContext ao
 * tenant do solicitante (withTenant), pois a trilha vive no banco do tenant enquanto
 * esta operação roda numa transação de controle.
 */
@Service
class UserManagementService(
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val invitationRepository: UserInvitationRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authService: AuthService,
    private val auditLogService: AuditLogService,
) {

    @Transactional("controlTransactionManager", readOnly = true)
    fun listUsers(): List<UserResponse> =
        userRepository.findAllByTenantId(principal().tenantUuid).map { UserResponse.from(it) }

    @Transactional("controlTransactionManager")
    fun invite(req: InviteRequest): InviteResponse {
        val p = principal()
        val tenantId = p.tenantUuid
        val role = parseAssignableRole(req.role)
        val email = req.email.trim().lowercase()
        if (invitationRepository.existsByTenantIdAndEmailIgnoreCaseAndStatus(tenantId, email, InvitationStatus.PENDING)) {
            throw ConflictException("Já existe um convite pendente para este e-mail")
        }
        // Token CRU só agora; o banco guarda apenas o hash. Expira em 72h.
        val rawToken = UUID.randomUUID().toString()
        val inv = invitationRepository.save(
            UserInvitation(
                tenantId = tenantId,
                email = email,
                tokenHash = sha256(rawToken),
                invitedByUserId = p.userId,
                expiresAt = Instant.now().plus(72, ChronoUnit.HOURS),
                role = role,
                status = InvitationStatus.PENDING,
            ),
        )
        audit(p, "user.invite", "user", null, after = mapOf("email" to email, "role" to role.name))
        return InviteResponse(
            id = inv.id!!,
            email = email,
            role = role.name,
            status = inv.status.name,
            expiresAt = inv.expiresAt,
            inviteLink = "/aceitar-convite?token=$rawToken",
        )
    }

    @Transactional("controlTransactionManager")
    fun changeRole(userId: UUID, req: ChangeRoleRequest): UserResponse {
        val p = principal()
        val user = loadScoped(userId, p.tenantUuid)
        val newRole = parseAssignableRole(req.role)
        val oldRole = user.role
        // Anti-lockout: não deixar rebaixar o ÚNICO admin ativo do tenant.
        if (oldRole == UserRole.ADMIN && newRole != UserRole.ADMIN) {
            guardLastAdmin(user)
        }
        user.role = newRole
        userRepository.save(user)
        audit(
            p, "user.role_change", "user", userId,
            before = mapOf("role" to oldRole.name), after = mapOf("role" to newRole.name),
        )
        return UserResponse.from(user)
    }

    @Transactional("controlTransactionManager")
    fun setStatus(userId: UUID, req: ActivateRequest): UserResponse {
        val p = principal()
        val user = loadScoped(userId, p.tenantUuid)
        val wasActive = user.isActive
        // Anti-lockout: não deixar desativar o ÚNICO admin ativo do tenant.
        if (wasActive && !req.active && user.role == UserRole.ADMIN) {
            guardLastAdmin(user)
        }
        user.isActive = req.active
        userRepository.save(user)
        val action = if (req.active) "user.reactivate" else "user.deactivate"
        audit(
            p, action, "user", userId,
            before = mapOf("isActive" to wasActive), after = mapOf("isActive" to req.active),
        )
        return UserResponse.from(user)
    }

    @Transactional("controlTransactionManager", readOnly = true)
    fun listPendingInvitations(): List<InvitationResponse> =
        invitationRepository
            .findAllByTenantIdAndStatus(principal().tenantUuid, InvitationStatus.PENDING)
            .map { InvitationResponse.from(it) }

    @Transactional("controlTransactionManager")
    fun revokeInvitation(id: UUID): RevokeResponse {
        val p = principal()
        val inv = invitationRepository.findByIdAndTenantId(id, p.tenantUuid)
            ?: throw ResourceNotFoundException("Convite não encontrado")
        // Idempotente: só PENDING vira REVOKED; aceito/expirado fica como está.
        if (inv.status == InvitationStatus.PENDING) {
            inv.status = InvitationStatus.REVOKED
            invitationRepository.save(inv)
        }
        return RevokeResponse(inv.id!!, inv.status.name)
    }

    /**
     * Aceite de convite — PÚBLICO (sem auth). Valida hash do token + PENDING + não
     * expirado, cria/atualiza o usuário (firstName/lastName/senha BCrypt/ativo),
     * marca o convite como ACCEPTED e devolve uma sessão (tokens) para login imediato.
     */
    @Transactional("controlTransactionManager")
    fun acceptInvite(req: AcceptInviteRequest): TokenResponse {
        val inv = invitationRepository.findByTokenHash(sha256(req.token))
            ?: throw UnauthorizedException("Convite inválido")
        if (inv.status != InvitationStatus.PENDING) {
            throw BusinessException("Convite já utilizado ou revogado")
        }
        if (inv.expiresAt.isBefore(Instant.now())) {
            inv.status = InvitationStatus.EXPIRED
            invitationRepository.save(inv)
            throw BusinessException("Convite expirado")
        }
        val tenant = tenantRepository.findById(inv.tenantId).orElse(null)?.takeIf { it.isActive }
            ?: throw BusinessException("Restaurante indisponível")

        val user = userRepository.findByTenantIdAndEmail(inv.tenantId, inv.email)
            ?: User(
                tenantId = inv.tenantId,
                email = inv.email,
                passwordHash = "",
                firstName = req.firstName.trim(),
            )
        user.firstName = req.firstName.trim()
        user.lastName = req.lastName.trim()
        user.passwordHash = passwordEncoder.encode(req.password)
        user.role = inv.role
        user.isActive = true
        val saved = userRepository.save(user)

        inv.status = InvitationStatus.ACCEPTED
        inv.acceptedAt = Instant.now()
        invitationRepository.save(inv)

        // Emite e persiste tokens (refresh token armazenado no banco do tenant).
        return authService.issueSession(saved, tenant)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun principal(): AuthPrincipal = SecurityUtils.currentPrincipalOrThrow()

    /** Carrega o usuário e exige que seja do MESMO tenant (anti-IDOR cross-tenant). */
    private fun loadScoped(userId: UUID, tenantId: UUID): User {
        val user = userRepository.findById(userId).orElse(null)
            ?: throw ResourceNotFoundException("Usuário não encontrado")
        if (user.tenantId != tenantId) throw ResourceNotFoundException("Usuário não encontrado")
        return user
    }

    /** Bloqueia (409) se [user] é o único ADMIN ativo do tenant. */
    private fun guardLastAdmin(user: User) {
        val activeAdmins = userRepository.countByTenantIdAndRoleAndIsActiveTrue(user.tenantId, UserRole.ADMIN)
        if (activeAdmins <= 1) {
            throw ConflictException("Não é possível rebaixar/desativar o único administrador ativo")
        }
    }

    /**
     * Converte e valida o papel. SUPER_ADMIN (papel de PLATAFORMA, cross-tenant) só
     * pode ser atribuído por quem JÁ é SUPER_ADMIN — assim um super-admin promove
     * outro sem UPDATE manual no banco, mas um ADMIN de restaurante nunca escala
     * privilégio para fora do próprio tenant.
     */
    private fun parseAssignableRole(raw: String): UserRole {
        val role = try {
            UserRole.valueOf(raw.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            throw BusinessException("Papel inválido: $raw")
        }
        if (role == UserRole.SUPER_ADMIN) {
            val actorIsSuperAdmin = principal().roles.any { it == UserRole.SUPER_ADMIN.name }
            if (!actorIsSuperAdmin) {
                throw BusinessException("Papel não atribuível por um administrador de restaurante")
            }
        }
        return role
    }

    private fun audit(
        p: AuthPrincipal,
        action: String,
        entity: String,
        entityId: UUID?,
        before: Any? = null,
        after: Any? = null,
    ) {
        // A trilha vive no banco do tenant; ligamos o contexto ao tenant do solicitante
        // para o REQUIRED do AuditLogService abrir a transação no banco certo.
        withTenant(p.tenantSlug) {
            auditLogService.log(
                action = action,
                entity = entity,
                entityId = entityId,
                before = before,
                after = after,
                actorUserId = p.userId,
                actorRole = p.roles.firstOrNull(),
            )
        }
    }

    private fun <T> withTenant(slug: String, block: () -> T): T {
        val previous = TenantContext.get()
        TenantContext.set(slug)
        try {
            return block()
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

package com.menuflow.platform

import com.menuflow.dto.InviteRequest
import com.menuflow.dto.InviteResponse
import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ForbiddenException
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.security.SecurityUtils
import com.menuflow.service.TotpService
import com.menuflow.service.UserManagementService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Gestao de usuarios de plataforma (SUPER_ADMINs).
 *
 * Diferente do UserManagementService (escopado por tenant do principal), este
 * service opera cross-tenant por design: lista todos os SUPER_ADMINs do sistema
 * e permite revogar/rebaixar qualquer um deles.
 *
 * Invariantes:
 *  - Anti-lockout: nunca revogar o ultimo SUPER_ADMIN ativo (409)
 *  - Anti-auto-revogacao: o principal nao pode revogar a si mesmo (403)
 *  - BOLA: carregar usuario por id e validar que ele e SUPER_ADMIN antes de agir
 */
@Service
class PlatformUserService(
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val totpService: TotpService,
    private val userManagementService: UserManagementService,
) {

    /** Lista todos os SUPER_ADMINs do sistema (cross-tenant). */
    @Transactional("controlTransactionManager", readOnly = true)
    fun listSuperAdmins(): List<PlatformUserSummary> {
        val admins = userRepository.findAllByRole(UserRole.SUPER_ADMIN)
        // mapeia tenant slug: carrega todos os tenants envolvidos em 1 query extra
        val tenantIds = admins.map { it.tenantId }.toSet()
        val tenantMap = tenantRepository.findAllById(tenantIds).associateBy { it.id!! }

        return admins.map { user ->
            val slug = tenantMap[user.tenantId]?.slug ?: user.tenantId.toString()
            PlatformUserSummary(
                id = user.id!!,
                name = user.fullName,
                email = user.email,
                tenantSlug = slug,
                createdAt = user.createdAt,
                lastLoginAt = user.lastLoginAt,
                has2FA = totpService.hasSecret(user.id!!),
            )
        }
    }

    /**
     * Convida um novo SUPER_ADMIN. O convite e criado no tenant do solicitante
     * (SUPER_ADMINs pertencem a um tenant real — nao ha "tenant de plataforma"
     * separado na arquitetura atual).
     *
     * Delega para UserManagementService.invite que ja contem:
     *  - anti-duplicata de convite pendente
     *  - geracao de token (hash SHA-256 armazenado, raw so no response)
     *  - auditoria
     */
    fun inviteSuperAdmin(email: String): InviteResponse =
        userManagementService.invite(InviteRequest(email = email, role = UserRole.SUPER_ADMIN.name))

    /**
     * Revoga o papel de SUPER_ADMIN de um usuario (rebaixa para ADMIN).
     * O usuario continua ativo no seu tenant, mas perde o acesso cross-tenant
     * ao painel de plataforma.
     *
     * Retorna 409 se for o ultimo SUPER_ADMIN ativo.
     * Retorna 403 se o principal tentar revogar a si mesmo.
     */
    @Transactional("controlTransactionManager")
    fun revokeSuperAdmin(targetId: UUID) {
        val principal = SecurityUtils.currentPrincipalOrThrow()

        // Anti-auto-revogacao
        if (principal.userId == targetId) {
            throw ForbiddenException("Nao e possivel revogar o proprio acesso")
        }

        // BOLA: carrega e valida que o alvo e SUPER_ADMIN
        val user = userRepository.findById(targetId).orElse(null)
            ?: throw BusinessException("Usuario nao encontrado")
        if (user.role != UserRole.SUPER_ADMIN) {
            throw BusinessException("Usuario nao e SUPER_ADMIN")
        }

        // Anti-lockout: conta SUPER_ADMINs ativos apos a remocao (o alvo ainda conta)
        val activeCount = userRepository.countByRoleAndIsActiveTrue(UserRole.SUPER_ADMIN)
        if (activeCount <= 1) {
            throw ConflictException("Ultimo super-admin ativo — nao e possivel revogar")
        }

        // Rebaixa para ADMIN (nao desativa — o usuario continua no seu tenant)
        user.role = UserRole.ADMIN
        userRepository.save(user)
    }
}

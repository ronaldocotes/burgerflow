package com.menuflow.platform

import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Bootstrap do PRIMEIRO super-admin sem UPDATE manual no banco. Se a env var
 * PLATFORM_BOOTSTRAP_EMAIL estiver definida, promove esse e-mail a SUPER_ADMIN no
 * boot (idempotente: se já for SUPER_ADMIN, não faz nada).
 *
 * O e-mail sozinho não é único entre tenants (login é escopado por tenant). Se o
 * e-mail existir em mais de um tenant, defina PLATFORM_BOOTSTRAP_TENANT (slug) para
 * desambiguar; sem isso, o bootstrap loga aviso e não altera nada (fail-safe: nunca
 * promove o usuário errado).
 *
 * Escreve no banco de CONTROLE via TransactionTemplate (ApplicationRunner não é
 * transacional; @Transactional no próprio bean não passaria pelo proxy). Falha do
 * bootstrap nunca derruba o app — só loga.
 */
@Component
class PlatformBootstrapRunner(
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    @Qualifier("controlTransactionManager")
    controlTxManager: PlatformTransactionManager,
    @Value("\${PLATFORM_BOOTSTRAP_EMAIL:}")
    private val bootstrapEmail: String,
    @Value("\${PLATFORM_BOOTSTRAP_TENANT:}")
    private val bootstrapTenant: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val controlTx = TransactionTemplate(controlTxManager)

    override fun run(args: ApplicationArguments) {
        val email = bootstrapEmail.trim().lowercase()
        if (email.isBlank()) return // Sem env => bootstrap desligado (caso normal).

        try {
            controlTx.executeWithoutResult {
                val tenantSlug = bootstrapTenant.trim().lowercase()
                val user = if (tenantSlug.isNotBlank()) {
                    val tenant = tenantRepository.findBySlug(tenantSlug)
                    if (tenant == null) {
                        log.warn("[PLATFORM BOOTSTRAP] Tenant '{}' não existe; nada a promover", tenantSlug)
                        return@executeWithoutResult
                    }
                    userRepository.findByTenantIdAndEmail(tenant.id!!, email)
                } else {
                    val matches = userRepository.findAllByEmail(email)
                    when {
                        matches.isEmpty() -> null
                        matches.size == 1 -> matches.first()
                        else -> {
                            log.warn(
                                "[PLATFORM BOOTSTRAP] E-mail '{}' existe em {} tenants; " +
                                    "defina PLATFORM_BOOTSTRAP_TENANT para desambiguar",
                                email, matches.size,
                            )
                            return@executeWithoutResult
                        }
                    }
                }

                if (user == null) {
                    log.warn("[PLATFORM BOOTSTRAP] Nenhum usuário com e-mail '{}' encontrado", email)
                    return@executeWithoutResult
                }
                if (user.role == UserRole.SUPER_ADMIN) {
                    log.info("[PLATFORM BOOTSTRAP] '{}' já é SUPER_ADMIN (no-op)", email)
                    return@executeWithoutResult
                }
                user.role = UserRole.SUPER_ADMIN
                user.isActive = true
                userRepository.save(user)
                log.warn("[PLATFORM BOOTSTRAP] '{}' promovido a SUPER_ADMIN", email)
            }
        } catch (ex: Exception) {
            log.error("[PLATFORM BOOTSTRAP] Falha ao promover '{}' (app segue de pé)", email, ex)
        }
    }
}

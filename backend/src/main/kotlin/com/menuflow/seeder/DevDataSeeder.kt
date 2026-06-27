package com.menuflow.seeder

import com.menuflow.model.Category
import com.menuflow.model.Product
import com.menuflow.model.RestaurantTable
import com.menuflow.model.control.Tenant
import com.menuflow.model.control.User
import com.menuflow.model.control.UserRole
import com.menuflow.repository.control.TenantRepository
import com.menuflow.repository.control.UserRepository
import com.menuflow.repository.tenant.CategoryRepository
import com.menuflow.repository.tenant.ProductRepository
import com.menuflow.repository.tenant.RestaurantTableRepository
import com.menuflow.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Semeador de DESENVOLVIMENTO. Só roda no perfil `dev` (nunca em `test`/`prod`),
 * e pode ser desligado com `menuflow.dev.seed=false`. Ao subir o backend garante,
 * de forma IDEMPOTENTE, que existe um tenant demo navegável com um admin de
 * credenciais fixas, alguns produtos e mesas — para que web/app subam prontos
 * para login sem ninguém precisar adivinhar senha (bcrypt não é reversível).
 *
 * Por que ApplicationRunner e não @PostConstruct: o seeder toca o banco do TENANT,
 * cujo DataSource roteado (DynamicTenantRoutingDataSource) provisiona o banco
 * físico `tenant_demo` + roda o Flyway no PRIMEIRO acesso. Isso exige o contexto
 * já totalmente inicializado; ApplicationRunner roda depois do boot completo.
 *
 * Multi-tenant aqui é DB-POR-TENANT: as escritas de controle (tenant/usuário) usam
 * o controlTransactionManager; as de negócio (categoria/produto/mesa) exigem
 * TenantContext ligado em "demo" + tenantTransactionManager. Usamos dois
 * TransactionTemplate (em vez de @Transactional no próprio bean) para evitar o
 * problema de auto-invocação do proxy do Spring (um método @Transactional chamado
 * de dentro da mesma classe não passa pelo proxy e não abre transação).
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = ["menuflow.dev.seed"], havingValue = "true", matchIfMissing = true)
class DevDataSeeder(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val categoryRepository: CategoryRepository,
    private val productRepository: ProductRepository,
    private val tableRepository: RestaurantTableRepository,
    @Qualifier("controlTransactionManager")
    controlTxManager: PlatformTransactionManager,
    @Qualifier("tenantTransactionManager")
    tenantTxManager: PlatformTransactionManager,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    // TransactionTemplate é a forma programática de abrir uma transação no manager
    // certo a partir do runner (que não é transacional por si só).
    private val controlTx = TransactionTemplate(controlTxManager)
    private val tenantTx = TransactionTemplate(tenantTxManager)

    override fun run(args: ApplicationArguments) {
        try {
            seedTenantAndAdmin()
            seedProductsAndTables()
            log.info(
                "[DEV SEEDER] Tenant demo provisionado: login {} / senha {} (tenantSlug={})",
                ADMIN_EMAIL, ADMIN_PASSWORD, DEMO_SLUG,
            )
        } catch (ex: Exception) {
            // Falha do seeder NÃO deve derrubar o app em dev — só logar bem alto.
            log.error("[DEV SEEDER] Falhou ao semear o tenant demo (app segue de pé)", ex)
        }
    }

    /**
     * Cria o tenant "demo" e o usuário admin no banco de CONTROLE, de forma
     * idempotente. Provisionar o banco físico do tenant + Flyway acontece de
     * graça no primeiro acesso a um repositório de tenant (ver seedProductsAndTables).
     */
    private fun seedTenantAndAdmin() {
        controlTx.executeWithoutResult {
            val tenant = tenantRepository.findBySlug(DEMO_SLUG)
                ?: tenantRepository.save(
                    Tenant(slug = DEMO_SLUG, displayName = DEMO_NAME),
                ).also { log.info("[DEV SEEDER] Tenant '{}' criado", DEMO_SLUG) }

            // Login é escopado por (tenantId, email); email guardado em minúsculas
            // porque o AuthService faz request.email.lowercase() na comparação.
            val existing = userRepository.findByTenantIdAndEmail(tenant.id!!, ADMIN_EMAIL)
            if (existing == null) {
                userRepository.save(
                    User(
                        tenantId = tenant.id!!,
                        email = ADMIN_EMAIL,
                        passwordHash = passwordEncoder.encode(ADMIN_PASSWORD),
                        firstName = "Admin",
                        lastName = "Demo",
                        role = UserRole.ADMIN,
                    ),
                )
                log.info("[DEV SEEDER] Usuário admin '{}' criado (papel ADMIN)", ADMIN_EMAIL)
            }
        }
    }

    /**
     * Semeia categorias, produtos e mesas no banco do TENANT demo. Liga o
     * TenantContext em "demo" (o que dispara, no 1º acesso, a criação do banco
     * físico tenant_demo + Flyway via o DataSource roteado) e roda tudo na
     * transação do tenant. Idempotente: cada item só é criado se ausente.
     */
    private fun seedProductsAndTables() {
        TenantContext.set(DEMO_SLUG)
        try {
            tenantTx.executeWithoutResult {
                val lanches = ensureCategory("Lanches", displayOrder = 1)
                val bebidas = ensureCategory("Bebidas", displayOrder = 2)

                ensureProduct("DEMO-XBURGUER", "X-Burguer", 3500, lanches.id!!, order = 1)
                ensureProduct("DEMO-BATATA", "Batata Frita", 1800, lanches.id!!, order = 2)
                ensureProduct("DEMO-COMBO", "Combo X-Burguer + Batata + Refri", 5200, lanches.id!!, order = 3)
                ensureProduct("DEMO-COCA", "Coca-Cola Lata 350ml", 800, bebidas.id!!, order = 1)

                (1..3).forEach { n -> ensureTable("Mesa $n", sortOrder = n) }
            }
        } finally {
            TenantContext.clear()
        }
    }

    private fun ensureCategory(name: String, displayOrder: Int): Category =
        categoryRepository.findByName(name)
            ?: categoryRepository.save(Category(name = name, displayOrder = displayOrder))
                .also { log.info("[DEV SEEDER] Categoria '{}' criada", name) }

    private fun ensureProduct(sku: String, name: String, priceCents: Long, categoryId: UUID, order: Int) {
        if (productRepository.existsBySku(sku)) return
        productRepository.save(
            Product(
                categoryId = categoryId,
                sku = sku,
                name = name,
                priceCents = priceCents,
                displayOrder = order,
            ),
        )
        log.info("[DEV SEEDER] Produto '{}' ({}) criado por {} centavos", name, sku, priceCents)
    }

    private fun ensureTable(label: String, sortOrder: Int) {
        if (tableRepository.existsByLabelAndActiveTrue(label)) return
        tableRepository.save(RestaurantTable(label = label, seats = 4, sortOrder = sortOrder))
        log.info("[DEV SEEDER] Mesa '{}' criada", label)
    }

    private companion object {
        const val DEMO_SLUG = "demo"
        const val DEMO_NAME = "Demo Restaurante"
        const val ADMIN_EMAIL = "admin@demo.com"
        const val ADMIN_PASSWORD = "Demo@1234"
    }
}

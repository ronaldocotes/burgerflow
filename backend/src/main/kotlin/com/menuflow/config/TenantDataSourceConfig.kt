package com.menuflow.config

import com.menuflow.model.Category
import com.menuflow.model.Customer
import com.menuflow.model.DeliveryDriver
import com.menuflow.model.IdempotencyKey
import com.menuflow.model.Ingredient
import com.menuflow.model.Order
import com.menuflow.model.OrderItem
import com.menuflow.model.Payment
import com.menuflow.model.Product
import com.menuflow.model.ProductIngredient
import com.menuflow.model.RefreshToken
import com.menuflow.tenant.DynamicTenantRoutingDataSource
import com.menuflow.tenant.TenantDataSourceProperties
import com.menuflow.tenant.TenantFlywayMigrator
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * TENANT persistence unit: all business data. Backed by the routing datasource,
 * so every connection lands on the physical DB of the current tenant. Business
 * services MUST use @Transactional("tenantTransactionManager").
 */
@Configuration
@EnableJpaRepositories(
    basePackages = ["com.menuflow.repository.tenant"],
    entityManagerFactoryRef = "tenantEntityManagerFactory",
    transactionManagerRef = "tenantTransactionManager",
)
class TenantDataSourceConfig {

    @Bean
    fun tenantRoutingDataSource(
        props: TenantDataSourceProperties,
        flywayMigrator: TenantFlywayMigrator,
    ): DynamicTenantRoutingDataSource {
        val ds = DynamicTenantRoutingDataSource(props)
        // Each NEW tenant database is migrated to HEAD via Flyway on first access
        // (versioned, idempotent, auditable) and the result is logged to the
        // control DB ledger. Replaces the old ScriptUtils baseline path.
        ds.schemaInitializer = { slug, dataSource -> flywayMigrator.migrate(slug, dataSource) }
        return ds
    }

    @Bean
    fun tenantEntityManagerFactory(
        builder: EntityManagerFactoryBuilder,
        @Qualifier("tenantRoutingDataSource") dataSource: DynamicTenantRoutingDataSource,
    ): LocalContainerEntityManagerFactoryBean =
        builder
            .dataSource(dataSource)
            // Explicit entity classes so model.control is NOT scanned into this PU.
            .packages(
                Product::class.java,
                Category::class.java,
                Order::class.java,
                OrderItem::class.java,
                Ingredient::class.java,
                ProductIngredient::class.java,
                Customer::class.java,
                IdempotencyKey::class.java,
                Payment::class.java,
                DeliveryDriver::class.java,
                RefreshToken::class.java,
            )
            .persistenceUnit("tenant")
            .properties(
                mapOf(
                    // DDL is NOT run at EMF boot (no tenant is bound yet). Schema is
                    // materialized per-tenant by TenantFlywayMigrator on first access.
                    "hibernate.hbm2ddl.auto" to "none",
                    // Dialect must be explicit: the routing datasource is lazy, so
                    // Hibernate cannot auto-detect it from a connection at startup.
                    "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect",
                    // Do NOT probe the DB for JDBC metadata when the EMF is built:
                    // EMF init can happen before any tenant is bound (e.g. on the
                    // first refresh/login that touches the tenant PU), and the
                    // routing datasource would throw "no tenant bound". With the
                    // dialect set explicitly the probe is unnecessary. (Hibernate
                    // 6.6 still attempts it unless this is false.)
                    "hibernate.boot.allow_jdbc_metadata_access" to "false",
                ),
            )
            .build()

    @Bean
    fun tenantTransactionManager(
        @Qualifier("tenantEntityManagerFactory") emf: EntityManagerFactory,
    ): PlatformTransactionManager = JpaTransactionManager(emf)
}

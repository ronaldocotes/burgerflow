package com.burgerflow.config

import com.burgerflow.model.Category
import com.burgerflow.model.Customer
import com.burgerflow.model.IdempotencyKey
import com.burgerflow.model.Ingredient
import com.burgerflow.model.Order
import com.burgerflow.model.OrderItem
import com.burgerflow.model.Product
import com.burgerflow.model.ProductIngredient
import com.burgerflow.tenant.DynamicTenantRoutingDataSource
import com.burgerflow.tenant.TenantDataSourceProperties
import com.burgerflow.tenant.TenantFlywayMigrator
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
    basePackages = ["com.burgerflow.repository.tenant"],
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
                ),
            )
            .build()

    @Bean
    fun tenantTransactionManager(
        @Qualifier("tenantEntityManagerFactory") emf: EntityManagerFactory,
    ): PlatformTransactionManager = JpaTransactionManager(emf)
}

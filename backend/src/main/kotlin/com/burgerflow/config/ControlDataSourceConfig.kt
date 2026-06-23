package com.burgerflow.config

import com.zaxxer.hikari.HikariDataSource
import com.burgerflow.tenant.TenantDataSourceProperties
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * CONTROL persistence unit: tenant + user registry (auth). Separate physical DB
 * (burgerflow_control), separate EntityManagerFactory and transaction manager
 * ("controlTx"). Marked @Primary so plain @Transactional/@Autowired default here.
 */
@Configuration
@EnableJpaRepositories(
    basePackages = ["com.burgerflow.repository.control"],
    entityManagerFactoryRef = "controlEntityManagerFactory",
    transactionManagerRef = "controlTransactionManager",
)
class ControlDataSourceConfig {

    @Bean
    @Primary
    fun controlDataSource(props: TenantDataSourceProperties): DataSource {
        val ds = HikariDataSource()
        ds.jdbcUrl = "jdbc:postgresql://${props.host}/${props.controlDb}"
        ds.username = props.username
        ds.password = props.password
        ds.driverClassName = "org.postgresql.Driver"
        ds.maximumPoolSize = 5
        ds.poolName = "bf-control"
        return ds
    }

    @Bean
    @Primary
    fun controlEntityManagerFactory(
        builder: EntityManagerFactoryBuilder,
        @Qualifier("controlDataSource") dataSource: DataSource,
    ): LocalContainerEntityManagerFactoryBean =
        builder
            .dataSource(dataSource)
            .packages("com.burgerflow.model.control")
            .persistenceUnit("control")
            .properties(
                mapOf(
                    "hibernate.hbm2ddl.auto" to "update",
                    "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect",
                ),
            )
            .build()

    @Bean
    @Primary
    fun controlTransactionManager(
        @Qualifier("controlEntityManagerFactory") emf: EntityManagerFactory,
    ): PlatformTransactionManager = JpaTransactionManager(emf)
}

package com.menuflow

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Datasource auto-configuration is excluded: we define the CONTROL datasource and
 * the per-tenant routing datasource explicitly (database-per-tenant). JPA
 * repositories are enabled per-PU in ControlDataSourceConfig / TenantDataSourceConfig.
 */
@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableCaching
@EnableAsync
@EnableScheduling
class MenuFlowApplication

fun main(args: Array<String>) {
    runApplication<MenuFlowApplication>(*args)
}

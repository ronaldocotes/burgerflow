package com.burgerflow.config

import org.hibernate.engine.jdbc.connections.spi.AbstractDataSourceBasedMultiTenantConnectionProviderImpl
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
class MultiTenantConnectionProvider : AbstractDataSourceBasedMultiTenantConnectionProviderImpl() {
    
    @Autowired
    private lateinit var dataSource: DataSource
    
    override fun selectAnyDataSource(): DataSource {
        return dataSource
    }
    
    override fun selectDataSource(tenantIdentifier: String?): DataSource {
        // For schema-based multi-tenancy, we use the same datasource
        // The tenant identifier will be used in the SQL queries
        return dataSource
    }
}

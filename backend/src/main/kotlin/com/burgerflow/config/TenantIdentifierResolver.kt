package com.burgerflow.config

import org.hibernate.context.spi.CurrentTenantIdentifierResolver
import org.springframework.stereotype.Component

@Component
class TenantIdentifierResolver : CurrentTenantIdentifierResolver<String> {
    
    companion object {
        private val threadLocal = ThreadLocal<String>()
        const val DEFAULT_TENANT = "public"
    }
    
    override fun resolveCurrentTenantIdentifier(): String {
        return threadLocal.get() ?: DEFAULT_TENANT
    }
    
    override fun validateExistingCurrentSessions(): Boolean {
        return true
    }
    
    fun setTenantId(tenantId: String) {
        threadLocal.set(tenantId)
    }
    
    fun clear() {
        threadLocal.remove()
    }
}

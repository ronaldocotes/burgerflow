package com.burgerflow.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

class TenantFilter(private val tenantIdentifierResolver: TenantIdentifierResolver) : OncePerRequestFilter() {
    
    companion object {
        private const val TENANT_HEADER = "X-Tenant-ID"
        private const val TENANT_PARAM = "tenantId"
    }
    
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            // Extract tenant ID from header, parameter, or JWT
            val tenantId = extractTenantId(request)
            
            if (tenantId != null) {
                tenantIdentifierResolver.setTenantId(tenantId)
            }
            
            filterChain.doFilter(request, response)
        } finally {
            tenantIdentifierResolver.clear()
        }
    }
    
    private fun extractTenantId(request: HttpServletRequest): String? {
        // Try header first
        val headerTenant = request.getHeader(TENANT_HEADER)
        if (headerTenant != null) {
            return headerTenant
        }
        
        // Try query parameter
        val paramTenant = request.getParameter(TENANT_PARAM)
        if (paramTenant != null) {
            return paramTenant
        }
        
        // Try to extract from JWT (if available)
        // This would be implemented with JWT parsing
        
        return null
    }
}

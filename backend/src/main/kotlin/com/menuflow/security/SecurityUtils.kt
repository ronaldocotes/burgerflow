package com.menuflow.security

import org.springframework.security.core.context.SecurityContextHolder

object SecurityUtils {

    fun currentPrincipal(): AuthPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthPrincipal

    fun currentPrincipalOrThrow(): AuthPrincipal =
        currentPrincipal() ?: throw IllegalStateException("No authenticated principal")
}

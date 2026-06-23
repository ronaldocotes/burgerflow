package com.burgerflow

import org.springframework.boot.test.context.TestComponent
import org.springframework.transaction.annotation.Transactional

/**
 * Runs a block inside the TENANT transaction manager so test seeding/reads hit
 * the routed tenant database (the default @Primary tx manager is the control DB).
 * The tenant must already be bound to TenantContext by the caller.
 */
@TestComponent
class TenantTestTx {
    @Transactional("tenantTransactionManager")
    fun <T> run(block: () -> T): T = block()
}

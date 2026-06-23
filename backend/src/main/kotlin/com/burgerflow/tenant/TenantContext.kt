package com.burgerflow.tenant

/**
 * Holds the current tenant slug for the executing request thread.
 *
 * Multi-tenancy in BurgerFlow is DATABASE-PER-TENANT (one physical Postgres
 * database per hamburgueria), NOT schema-per-tenant. Isolation is therefore a
 * physical property of the routed [javax.sql.DataSource]; a forgotten WHERE
 * clause cannot leak across tenants because the connection points at a
 * different database entirely.
 *
 * The slug here is the logical tenant key (e.g. "abc"); the physical database
 * name is derived as `tenant_<slug>` by [DynamicTenantRoutingDataSource].
 */
object TenantContext {
    private val current = ThreadLocal<String?>()

    /** Reserved key used to reach the control database (tenants/users registry). */
    const val CONTROL = "__control__"

    fun set(slug: String) {
        current.set(slug)
    }

    fun get(): String? = current.get()

    fun getOrThrow(): String =
        current.get() ?: throw IllegalStateException("No tenant bound to the current request thread")

    fun clear() {
        current.remove()
    }
}

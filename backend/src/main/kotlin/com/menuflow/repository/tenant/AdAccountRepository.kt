package com.menuflow.repository.tenant

import com.menuflow.model.AdAccount
import com.menuflow.model.AdProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Contas de anuncio do tenant (banco do TENANT). Sem filtro de escopo: db-per-tenant
 * ja isola por banco. O upsert do reconnect usa
 * [findByProviderAndExternalAccountId] para achar a linha existente.
 */
@Repository
interface AdAccountRepository : JpaRepository<AdAccount, UUID> {

    fun findAllByOrderByCreatedAtAsc(): List<AdAccount>

    fun findByProviderAndExternalAccountId(provider: AdProvider, externalAccountId: String): AdAccount?
}

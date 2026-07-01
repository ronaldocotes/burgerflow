package com.menuflow.repository.control

import com.menuflow.model.control.IfoodAppConfig
import com.menuflow.model.control.IfoodAppRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** Repositorio (banco de CONTROLE) da credencial da aplicacao iFood. */
@Repository
interface IfoodAppConfigRepository : JpaRepository<IfoodAppConfig, UUID> {
    fun findByActiveTrue(): List<IfoodAppConfig>
    fun findByRole(role: IfoodAppRole): IfoodAppConfig?
}

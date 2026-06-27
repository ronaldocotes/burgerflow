package com.menuflow.repository.tenant

import com.menuflow.model.TenantConfig
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TenantConfigRepository : JpaRepository<TenantConfig, UUID> {

    /**
     * A única linha de configuração do tenant (db-per-tenant garante no máximo
     * uma). Ordena por created_at só por determinismo caso, por algum reprovisiona-
     * mento, exista mais de uma. Ausente = tenant ainda sem linha (default aplicado
     * pela camada de serviço).
     */
    fun findFirstByOrderByCreatedAtAsc(): TenantConfig?
}

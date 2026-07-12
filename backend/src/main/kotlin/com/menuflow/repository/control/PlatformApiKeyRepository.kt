package com.menuflow.repository.control

import com.menuflow.model.control.PlatformApiKey
import com.menuflow.model.control.PlatformApiKeyProviderType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** Repositorio (banco de CONTROLE) das chaves de API da plataforma. */
@Repository
interface PlatformApiKeyRepository : JpaRepository<PlatformApiKey, UUID> {
    /**
     * A chave ATIVA de um provedor. O indice unico parcial garante no maximo uma; usa-se
     * findFirst por defesa (nunca lanca NonUnique mesmo se o invariante for violado).
     */
    fun findFirstByProviderAndActiveTrue(provider: PlatformApiKeyProviderType): PlatformApiKey?
}

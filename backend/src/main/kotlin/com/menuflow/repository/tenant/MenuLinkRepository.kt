package com.menuflow.repository.tenant

import com.menuflow.model.MenuLink
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface MenuLinkRepository : JpaRepository<MenuLink, UUID> {

    /** Todos os links na ordem de criacao (config admin). */
    fun findAllByOrderByCreatedAtAsc(): List<MenuLink>

    /** Resolucao publica: link ativo por slug. */
    fun findBySlugAndActiveTrue(slug: String): MenuLink?

    /** Colisao de slug entre links ativos (validacao de unicidade no upsert). */
    fun existsBySlugAndActiveTrue(slug: String): Boolean
}

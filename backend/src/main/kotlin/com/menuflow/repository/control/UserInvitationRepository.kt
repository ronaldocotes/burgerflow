package com.menuflow.repository.control

import com.menuflow.model.control.InvitationStatus
import com.menuflow.model.control.UserInvitation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserInvitationRepository : JpaRepository<UserInvitation, UUID> {
    fun findByTokenHash(tokenHash: String): UserInvitation?

    fun findAllByTenantIdAndStatus(tenantId: UUID, status: InvitationStatus): List<UserInvitation>

    /** Há convite pendente para este e-mail (case-insensitive) neste tenant? */
    fun existsByTenantIdAndEmailIgnoreCaseAndStatus(
        tenantId: UUID,
        email: String,
        status: InvitationStatus,
    ): Boolean

    /** Carga escopada por tenant (anti-IDOR cross-tenant na revogação). */
    fun findByIdAndTenantId(id: UUID, tenantId: UUID): UserInvitation?
}

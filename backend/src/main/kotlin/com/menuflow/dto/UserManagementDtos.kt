package com.menuflow.dto

import com.menuflow.model.control.User
import com.menuflow.model.control.UserInvitation
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class UserResponse(
    val id: UUID,
    val firstName: String?,
    val lastName: String?,
    val email: String,
    val role: String,
    val isActive: Boolean,
    val lastLoginAt: Instant?,
) {
    companion object {
        fun from(u: User) = UserResponse(
            id = u.id!!,
            firstName = u.firstName,
            lastName = u.lastName,
            email = u.email,
            role = u.role.name,
            isActive = u.isActive,
            lastLoginAt = u.lastLoginAt,
        )
    }
}

data class InviteRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val role: String,
)

data class InviteResponse(
    val id: UUID,
    val email: String,
    val role: String,
    val status: String,
    val expiresAt: Instant,
    /** Link com o token CRU — exibido só aqui, nunca persistido. */
    val inviteLink: String,
)

data class InvitationResponse(
    val id: UUID,
    val email: String,
    val role: String,
    val status: String,
    val expiresAt: Instant,
    val createdAt: Instant,
) {
    companion object {
        fun from(i: UserInvitation) = InvitationResponse(
            id = i.id!!,
            email = i.email,
            role = i.role.name,
            status = i.status.name,
            expiresAt = i.expiresAt,
            createdAt = i.createdAt,
        )
    }
}

data class ChangeRoleRequest(@field:NotBlank val role: String)

data class ActivateRequest(val active: Boolean)

data class AcceptInviteRequest(
    @field:NotBlank val token: String,
    @field:NotBlank val firstName: String,
    @field:NotBlank val lastName: String,
    @field:Size(min = 8) val password: String,
)

data class RevokeResponse(val id: UUID, val status: String)

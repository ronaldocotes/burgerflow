package com.menuflow.repository.tenant

import com.menuflow.model.IdempotencyKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, String>

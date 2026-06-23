package com.burgerflow.service

import com.burgerflow.exception.ConflictException
import com.burgerflow.model.IdempotencyKey
import com.burgerflow.repository.tenant.IdempotencyKeyRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

/**
 * Idempotency for unsafe POSTs (orders, products). Stores the first response for
 * a given Idempotency-Key in the TENANT database and re-serves it on retry.
 * Same key + different payload -> 409 (Stripe-style misuse rejection).
 *
 * The key is unique per tenant by construction (one DB per tenant), so the same
 * UUID submitted to two different hamburguerias never collides.
 */
@Service
class IdempotencyService(
    private val repository: IdempotencyKeyRepository,
    private val objectMapper: ObjectMapper,
) {

    data class Stored(val status: Int, val body: String)

    fun hash(payload: Any): String {
        val json = objectMapper.writeValueAsBytes(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(json)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Returns the stored response for this key, or null if first use. Validates payload match. */
    @Transactional("tenantTransactionManager", readOnly = true)
    fun find(key: String, scope: String, requestHash: String): Stored? {
        val existing = repository.findById(key).orElse(null) ?: return null
        if (existing.scope != scope || existing.requestHash != requestHash) {
            throw ConflictException("Idempotency-Key reused with a different request")
        }
        return Stored(existing.responseStatus, existing.responseBody)
    }

    /** Persists the first response. A racing concurrent insert surfaces as 409. */
    @Transactional("tenantTransactionManager")
    fun save(key: String, scope: String, requestHash: String, status: Int, responseObject: Any) {
        val body = objectMapper.writeValueAsString(responseObject)
        try {
            repository.save(
                IdempotencyKey(
                    key = key,
                    scope = scope,
                    requestHash = requestHash,
                    responseStatus = status,
                    responseBody = body,
                ),
            )
        } catch (ex: DataIntegrityViolationException) {
            throw ConflictException("Concurrent request with the same Idempotency-Key is in progress")
        }
    }

    fun <T> deserialize(body: String, type: Class<T>): T = objectMapper.readValue(body, type)
}

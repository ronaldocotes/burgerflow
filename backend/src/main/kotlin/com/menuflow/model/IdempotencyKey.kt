package com.menuflow.model

import jakarta.persistence.*
import java.time.Instant

/**
 * Stores the first response for a given Idempotency-Key so retries (double-click,
 * mobile offline re-send, network retry) re-serve the original result instead of
 * creating a duplicate. Lives in the TENANT database; the key is unique per
 * tenant by construction (one DB per tenant).
 */
@Entity
@Table(name = "idempotency_keys")
data class IdempotencyKey(
    @Id
    @Column(name = "key", length = 100)
    val key: String,

    /** Logical scope, e.g. "orders" or "products", to avoid cross-endpoint reuse. */
    @Column(name = "scope", nullable = false, length = 50)
    val scope: String,

    /** Hash of the request body to detect same-key/different-payload misuse. */
    @Column(name = "request_hash", nullable = false)
    val requestHash: String,

    @Column(name = "response_status", nullable = false)
    val responseStatus: Int,

    @Column(name = "response_body", nullable = false, columnDefinition = "text")
    val responseBody: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

package com.menuflow.security.ratelimit

/**
 * Per-key login rate limiter (Sprint 2). Key is the client IP. Implementations:
 *  - [InMemoryLoginRateLimiter] (default / dev / test): node-local Bucket4j.
 *  - RedisLoginRateLimiter (production): counters shared across instances.
 */
interface LoginRateLimiter {

    /** Tries to consume one token for [key]. */
    fun tryConsume(key: String): Decision

    /**
     * @param allowed     whether the request may proceed.
     * @param retryAfterSeconds seconds the client should wait before retrying
     *                          when [allowed] is false (>= 1).
     */
    data class Decision(
        val allowed: Boolean,
        val retryAfterSeconds: Long,
    )
}

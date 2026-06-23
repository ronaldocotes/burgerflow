package com.burgerflow.exception

/** 404 — entity does not exist in the current tenant database. */
class ResourceNotFoundException(message: String) : RuntimeException(message)

/** 400 — business rule violated with a malformed/invalid request. */
class BusinessException(message: String) : RuntimeException(message)

/** 401 — authentication failed (bad credentials / invalid token / tenant). */
class UnauthorizedException(message: String) : RuntimeException(message)

/** 409 — conflict (duplicate unique key, optimistic lock, idempotency-key reuse with different payload). */
class ConflictException(message: String) : RuntimeException(message)

/**
 * 422 — semantically valid request that cannot be fulfilled. Used for
 * insufficient ingredient stock when creating an order.
 */
class UnprocessableEntityException(
    message: String,
    val details: List<Map<String, Any?>> = emptyList(),
) : RuntimeException(message)

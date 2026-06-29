package com.menuflow.exception

/** 404 — entity does not exist in the current tenant database. */
class ResourceNotFoundException(message: String) : RuntimeException(message)

/** 400 — business rule violated with a malformed/invalid request. */
class BusinessException(message: String) : RuntimeException(message)

/** 401 — authentication failed (bad credentials / invalid token / tenant). */
class UnauthorizedException(message: String) : RuntimeException(message)

/**
 * 403 — usuario autenticado, mas a funcionalidade esta indisponivel/desativada para
 * ele com uma mensagem propria (ex.: Copiloto de IA desligado). Diferente do 403 do
 * Spring Security (@PreAuthorize), aqui a mensagem chega ao cliente.
 */
class ForbiddenException(message: String) : RuntimeException(message)

/** 429 — limite de uso atingido (ex.: teto diario de perguntas ao copiloto de IA). */
class TooManyRequestsException(message: String) : RuntimeException(message)

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

/**
 * 503 — dependencia externa indisponivel (ex.: Asaas fora do ar / circuito aberto).
 * Lancada pelos fallbacks do AsaasClient para o PDV receber um erro tratavel
 * ("PIX indisponivel"), nunca um 500 cru.
 */
class ServiceUnavailableException(message: String) : RuntimeException(message)

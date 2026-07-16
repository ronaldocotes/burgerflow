package com.menuflow.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.time.Instant

data class ErrorResponse(
    val timestamp: Instant,
    val status: Int,
    val error: String,
    val code: String,
    val message: String,
    val path: String,
    val errors: List<String> = emptyList(),
    val details: List<Map<String, Any?>> = emptyList(),
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private fun path(req: WebRequest) = req.getDescription(false).removePrefix("uri=")

    private fun body(
        status: HttpStatus,
        code: String,
        message: String?,
        req: WebRequest,
        errors: List<String> = emptyList(),
        details: List<Map<String, Any?>> = emptyList(),
    ) = ErrorResponse(
        timestamp = Instant.now(),
        status = status.value(),
        error = status.reasonPhrase,
        code = code,
        message = message ?: status.reasonPhrase,
        path = path(req),
        errors = errors,
        details = details,
    )

    @ExceptionHandler(ResourceNotFoundException::class)
    fun notFound(ex: ResourceNotFoundException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.message, req))

    @ExceptionHandler(BusinessException::class)
    fun business(ex: BusinessException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(body(HttpStatus.BAD_REQUEST, "BUSINESS_ERROR", ex.message, req))

    @ExceptionHandler(UnauthorizedException::class)
    fun unauthorized(ex: UnauthorizedException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header("WWW-Authenticate", "Bearer")
            .body(body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.message, req))

    @ExceptionHandler(ConflictException::class)
    fun conflict(ex: ConflictException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(body(HttpStatus.CONFLICT, "CONFLICT", ex.message, req))

    @ExceptionHandler(ForbiddenException::class)
    fun forbidden(ex: ForbiddenException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(body(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.message, req))

    @ExceptionHandler(TooManyRequestsException::class)
    fun tooManyRequests(ex: TooManyRequestsException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(body(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", ex.message, req))

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun optimisticLock(ex: ObjectOptimisticLockingFailureException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(body(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "Resource was modified concurrently; retry", req))

    @ExceptionHandler(UnprocessableEntityException::class)
    fun unprocessable(ex: UnprocessableEntityException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(body(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE", ex.message, req, details = ex.details))

    @ExceptionHandler(PayloadTooLargeException::class)
    fun payloadTooLarge(ex: PayloadTooLargeException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(body(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", ex.message, req))

    @ExceptionHandler(ServiceUnavailableException::class)
    fun serviceUnavailable(ex: ServiceUnavailableException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", ex.message, req))

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArg(ex: IllegalArgumentException, req: WebRequest) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.message, req))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(ex: MethodArgumentNotValidException, req: WebRequest): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", req, errors = errors))
    }

    /**
     * Method-security (@PreAuthorize) denial for an AUTHENTICATED user lacking the
     * required role -> 403. Without this explicit handler the catch-all below
     * turned every RBAC denial into a 500 (it affected ALL @PreAuthorize routes,
     * not just the admin one — surfaced by the SUPER_ADMIN drift-check test).
     * Must be declared BEFORE the catch-all so it wins for AccessDeniedException.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun accessDenied(
        ex: org.springframework.security.access.AccessDeniedException,
        req: WebRequest,
    ) = ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Você não tem permissão para executar esta ação", req))

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception, req: WebRequest): ResponseEntity<ErrorResponse> {
        org.slf4j.LoggerFactory.getLogger(javaClass).error("Unhandled exception on {}", path(req), ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", req))
    }
}

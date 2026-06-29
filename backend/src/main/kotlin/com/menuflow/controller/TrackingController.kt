package com.menuflow.controller

import com.menuflow.dto.TrackingLinkCreateRequest
import com.menuflow.dto.TrackingLinkResponse
import com.menuflow.dto.TrackingLinkUpdateRequest
import com.menuflow.dto.TrackingSummaryResponse
import com.menuflow.service.TrackingService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

/**
 * Painel de tracking first-party (Fase 3.6). Sob o context-path /api/v1 (logo
 * @RequestMapping = /tracking). Restrito a ADMIN/MANAGER (gestao de marketing). O
 * endpoint PUBLICO de clique fica em PublicMenuController (/public/{slug}/r/{trackingSlug}).
 */
@RestController
@RequestMapping("/tracking")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class TrackingController(private val service: TrackingService) {

    @GetMapping("/links")
    fun list(
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): Page<TrackingLinkResponse> = service.list(pageable)

    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: TrackingLinkCreateRequest): TrackingLinkResponse =
        service.create(req)

    @PatchMapping("/links/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: TrackingLinkUpdateRequest,
    ): TrackingLinkResponse = service.update(id, req)

    @DeleteMapping("/links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate(@PathVariable id: UUID) = service.deactivate(id)

    @GetMapping("/summary")
    fun summary(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): List<TrackingSummaryResponse> = service.getSummary(from, to)
}

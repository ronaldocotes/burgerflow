package com.menuflow.controller

import com.menuflow.dto.CreatePixQrRequest
import com.menuflow.dto.PaymentIntentResponse
import com.menuflow.service.PixPaymentService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Pagamento PIX do PDV. Sob o context-path /api/v1 (entao /payments). Geracao do QR
 * e ato de caixa (ADMIN/MANAGER/CASHIER); o status pode ser consultado tambem pela
 * operacao (STAFF) para acompanhar o pagamento. O banco e roteado pelo TenantContext
 * do token assinado (JwtAuthFilter) — isolamento garantido.
 */
@RestController
@RequestMapping("/payments")
class PixPaymentController(private val service: PixPaymentService) {

    @PostMapping("/pix-qr")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    fun createPixQr(@Valid @RequestBody req: CreatePixQrRequest): PaymentIntentResponse =
        service.createPixCharge(req.orderId)

    @GetMapping("/pix-qr/status/{orderId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER','STAFF')")
    fun getStatus(@PathVariable orderId: UUID): PaymentIntentResponse =
        service.getStatus(orderId)
}

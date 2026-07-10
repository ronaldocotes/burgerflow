package com.menuflow.ads

import com.menuflow.model.AdAccount
import com.menuflow.model.AdAccountStatus
import com.menuflow.model.AdProvider
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Corpo do POST /ads/accounts. O System User Token e gerado pelo cliente no Business
 * Manager dele e colado aqui uma unica vez; o backend valida e guarda cifrado.
 */
data class ConnectAdAccountRequest(
    @field:NotBlank
    @field:Size(min = 20, max = 500, message = "Token da Meta com tamanho invalido")
    val token: String,
)

/**
 * Conta de anuncio devolvida ao cliente. NUNCA carrega o token (nem cifrado). O
 * campo [accountIdLast4] e so um fingerprint para o usuario reconhecer qual conta e.
 */
data class AdAccountResponse(
    val id: UUID,
    val provider: AdProvider,
    val accountName: String?,
    val accountIdLast4: String,
    val currency: String?,
    val status: AdAccountStatus,
    val connectedAt: Instant,
) {
    companion object {
        fun from(a: AdAccount) = AdAccountResponse(
            id = a.id!!,
            provider = a.provider,
            accountName = a.accountName,
            accountIdLast4 = a.externalAccountId.takeLast(4),
            currency = a.currency,
            status = a.status,
            connectedAt = a.createdAt,
        )
    }
}

package com.menuflow.dto

import com.menuflow.model.TenantConfig
import jakarta.validation.constraints.Size

/**
 * Estado das configurações do tenant. Exposto em GET /config e devolvido por
 * PATCH /config. Campos futuros (novos toggles) entram aqui de forma aditiva.
 */
data class TenantConfigResponse(
    val autoAcceptOrders: Boolean,
    /** Chave PIX estatica do restaurante; null quando nao configurada. */
    val pixKey: String?,
) {
    companion object {
        fun from(c: TenantConfig) =
            TenantConfigResponse(autoAcceptOrders = c.autoAcceptOrders, pixKey = c.pixKey)
    }
}

/**
 * Atualização parcial das configurações (PATCH). Hoje só o aceite automático.
 * Boolean não-nulo: ausência do campo no corpo vira 400 (Jackson/Kotlin).
 */
data class TenantConfigUpdateRequest(
    val autoAcceptOrders: Boolean,
    /**
     * Chave PIX estatica. Nullable e nao obrigatorio no corpo. Por seguranca de
     * PATCH parcial, o service so sobrescreve quando vier NAO-nulo (cliente que
     * omite o campo nao apaga a chave ja salva). @Size limita ao tamanho da
     * coluna (VARCHAR(140)) — evita overflow virar 500.
     */
    @field:Size(max = 140)
    val pixKey: String? = null,
)

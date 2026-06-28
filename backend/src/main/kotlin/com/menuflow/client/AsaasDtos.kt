package com.menuflow.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Contratos MINIMOS da API Asaas (apenas os campos que consumimos). O Asaas envia
 * muitos outros campos; @JsonIgnoreProperties evita quebrar quando eles mudam.
 * Atencao: o Asaas usa valor DECIMAL em reais (value), nao centavos — a conversao
 * acontece na borda (PixPaymentService).
 */

/** Corpo de POST /payments (criar cobranca PIX). */
data class AsaasPaymentRequest(
    val billingType: String = "PIX",
    val customer: String,
    val value: Double,        // reais (decimal), NAO centavos
    val dueDate: String,      // "YYYY-MM-DD"
    val externalReference: String,
    val description: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AsaasPaymentResponse(
    val id: String,
    val status: String,
    val value: Double,
    val externalReference: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AsaasPixQrResponse(
    val encodedImage: String,
    val payload: String,
    val expirationDate: String?,
)

/** Corpo de POST /customers (customer avulso fixo por tenant). */
data class AsaasCustomerRequest(
    val name: String,
    val cpfCnpj: String? = null,
    val email: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AsaasCustomerResponse(
    val id: String,
)

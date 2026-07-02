package com.menuflow.dto

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * Fase C1 - auto-cadastro publico do motoboy freelancer.
 * O token (signup_token, UUID) e a "senha" de acesso ao link, enviado por WhatsApp.
 */

/** Dados minimos para pre-preencher o formulario. Nunca expoe CPF/endereco (LGPD). */
data class DeliveryDriverPreviewResponse(
    val name: String?,
    val phoneMasked: String, // "96 9****-1234"
    val provisional: Boolean,
    val alreadyCompleted: Boolean,
)

/** Corpo do POST de conclusao do cadastro. */
data class DriverRegistrationRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,

    // CPF opcional, mas quando presente precisa estar no formato mascarado.
    @field:Pattern(
        regexp = "\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}",
        message = "CPF deve estar no formato 000.000.000-00",
    )
    val cpf: String? = null,

    val cnhCategory: String? = null, // "A", "AB", "B"

    val vehicleType: String? = null, // "MOTO", "CARRO", "VAN"

    @field:NotBlank
    @field:Pattern(
        regexp = "[A-Z]{3}[0-9][A-Z0-9][0-9]{2}",
        message = "Placa invalida (Mercosul ou antiga)",
    )
    val licensePlate: String,

    @field:NotBlank
    @field:Size(max = 100)
    val pixKey: String,

    val pixKeyType: String? = null, // "CPF","PHONE","EMAIL","RANDOM"

    @field:Size(max = 255)
    val fullAddress: String? = null,

    // LGPD: aceite obrigatorio dos termos para concluir o cadastro.
    @field:AssertTrue(message = "Voce precisa aceitar os termos para concluir o cadastro")
    val acceptedTerms: Boolean = false,
)

data class DriverRegistrationResponse(val message: String)

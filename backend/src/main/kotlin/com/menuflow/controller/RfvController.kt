package com.menuflow.controller

import com.menuflow.dto.RfvScoreResponse
import com.menuflow.dto.RfvSummaryResponse
import com.menuflow.model.RfvSegment
import com.menuflow.service.RfvService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Classificacao RFV dos clientes (Fase 3.4). Sob o context-path /api/v1 (logo
 * @RequestMapping = /rfv). Restrito a ADMIN/MANAGER (dado analitico de negocio).
 * db-per-tenant: ja aterrissa no banco do restaurante.
 */
@RestController
@RequestMapping("/rfv")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
class RfvController(private val service: RfvService) {

    /** Lista de scores RFV, opcionalmente filtrado por segmento. */
    @GetMapping
    fun scores(@RequestParam(required = false) segment: RfvSegment?): List<RfvScoreResponse> =
        service.scoresBySegment(segment).map { RfvScoreResponse.from(it) }

    /**
     * Sumario de contagem por segmento.
     * Ex.: { loyal: 12, atRisk: 34, inactive: 8, newCustomers: 5, total: 59 }
     */
    @GetMapping("/summary")
    fun summary(): RfvSummaryResponse = service.summary()

    /**
     * Exportacao CSV dos clientes por segmento (opcional).
     * Util para o operador baixar a lista e disparar campanhas manuais no WhatsApp.
     *
     * Campos: nome,telefone,recenciaDias,frequencia,totalReais,segmento
     * Valores monetarios em reais (dividido por 100, 2 casas decimais).
     * Campos com virgula/aspas sao escapados no padrao RFC 4180.
     */
    @GetMapping("/export", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun export(
        @RequestParam(required = false) segment: RfvSegment?,
        response: HttpServletResponse,
    ) {
        val segLabel = segment?.name?.lowercase() ?: "all"
        response.contentType = "text/csv; charset=UTF-8"
        response.setHeader("Content-Disposition", "attachment; filename=\"rfv-$segLabel.csv\"")

        val pw = response.writer
        pw.println("nome,telefone,recenciaDias,frequencia,totalReais,segmento")
        service.scoresBySegment(segment).forEach { score ->
            val totalReais = "%.2f".format(score.monetaryValue / 100.0)
            pw.println(
                listOf(
                    csvEscape(score.customerName ?: ""),
                    csvEscape(score.phoneNumber ?: ""),
                    score.recencyDays.toString(),
                    score.frequency.toString(),
                    totalReais,
                    score.segment.name,
                ).joinToString(","),
            )
        }
        pw.flush()
    }

    /** Escapa um campo CSV: envolve em aspas duplas se contiver virgula, aspa ou newline. */
    private fun csvEscape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"${value.replace("\"", "\"\"")}\"" else value
    }
}

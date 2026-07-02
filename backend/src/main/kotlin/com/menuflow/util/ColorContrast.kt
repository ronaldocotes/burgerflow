package com.menuflow.util

import kotlin.math.pow

/**
 * Contraste de cor conforme WCAG 2.x (issue #12). Usado para decidir a cor de
 * texto legivel sobre a cor de marca escolhida e informar ao frontend se o par
 * atinge o nivel AA (razao >= 4.5:1 para texto normal).
 *
 * FATO verificado (algebra do WCAG): para QUALQUER cor valida, o contraste
 * contra branco (Cw) e contra preto (Cb) satisfazem Cw * Cb = 21 (constante).
 * Logo max(Cw, Cb) >= sqrt(21) ~= 4.58 > 4.5 SEMPRE. Ou seja: toda cor tem ao
 * menos uma cor de texto (preto OU branco) que passa em AA. Por isso o backend
 * NAO rejeita a cor por contraste (seria codigo morto); ele valida so o formato
 * do hex e devolve a cor de texto recomendada + as razoes, para o frontend
 * pintar o texto certo e exibir o selo AA. Rejeitar por contraste enganaria o
 * dono sem nunca disparar.
 */
object ColorContrast {

    private val HEX = Regex("^#([0-9a-fA-F]{6})$")

    const val WHITE = "#FFFFFF"
    const val BLACK = "#000000"

    /** true se a string e um hex "#RRGGBB" valido. */
    fun isValidHex(hex: String): Boolean = HEX.matches(hex.trim())

    /** Normaliza para "#RRGGBB" maiusculo. Lanca se invalido. */
    fun normalize(hex: String): String {
        val t = hex.trim()
        require(isValidHex(t)) { "cor deve ser um hex no formato #RRGGBB" }
        return t.uppercase()
    }

    /** Luminancia relativa (0..1) conforme WCAG. */
    fun relativeLuminance(hex: String): Double {
        val h = normalize(hex).removePrefix("#")
        val r = channel(h.substring(0, 2).toInt(16))
        val g = channel(h.substring(2, 4).toInt(16))
        val b = channel(h.substring(4, 6).toInt(16))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Razao de contraste entre duas cores (>= 1.0), simetrica. */
    fun contrastRatio(a: String, b: String): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}

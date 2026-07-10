package com.menuflow.ads

import com.menuflow.model.AdMetricsSnapshot
import java.time.LocalDate

/**
 * Uma linha do grafico de metricas (Fase 8.1), a nivel de conta. Dinheiro em centavos na
 * moeda da conta (spendCents/cpcCents) — o frontend formata conforme a moeda; NUNCA float.
 * ctrMilli = CTR% * 1000 (ex.: 1500 = 1,5%). [isPartial] = true no dia corrente (numeros
 * ainda consolidando na Meta), para a UI poder marca-lo (ex.: "hoje, parcial").
 */
data class AdMetricsResponse(
    val date: LocalDate,
    val spendCents: Long,
    val impressions: Long,
    val reach: Long,
    val clicks: Long,
    val ctrMilli: Int,
    val cpcCents: Long,
    val isPartial: Boolean,
) {
    companion object {
        fun from(s: AdMetricsSnapshot) = AdMetricsResponse(
            date = s.snapshotDate,
            spendCents = s.spendCents,
            impressions = s.impressions,
            reach = s.reach,
            clicks = s.clicks,
            ctrMilli = s.ctrMilli,
            cpcCents = s.cpcCents,
            isPartial = s.isPartial,
        )
    }
}

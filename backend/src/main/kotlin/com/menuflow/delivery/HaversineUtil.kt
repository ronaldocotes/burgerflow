package com.menuflow.delivery

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distancia geografica pela formula de Haversine. Suficiente para o auto-assign
 * (ordenar entregadores por proximidade) sem depender de servico externo de rotas.
 */
object HaversineUtil {
    private const val EARTH_RADIUS_KM = 6371.0

    /** Distancia em linha reta (km) entre dois pontos (graus decimais). */
    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2).pow(2)
        return EARTH_RADIUS_KM * 2 * asin(sqrt(a))
    }

    /** Distancia de rua ESTIMADA: linha reta x 1.3 (fator de sinuosidade urbano). */
    fun estimatedRoadKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
        distanceKm(lat1, lng1, lat2, lng2) * 1.3
}

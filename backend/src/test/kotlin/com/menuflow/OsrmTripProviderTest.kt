package com.menuflow

import com.menuflow.dispatch.OsrmTripProvider
import com.menuflow.service.RouteOptimizationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Issue #4, F1 — logica pura da roteirizacao SEM Spring/Testcontainers/OSRM real:
 *  1. buildTripPath: origem primeiro, ordem LON,LAT, source=first & roundtrip=false;
 *  2. parseTrip: dado um corpo /trip com ordem otima conhecida, devolve a permutacao
 *     dos indices de ENTRADA na ordem certa + os totais (metros/segundos);
 *  3. parseTrip fail-fast: code != "Ok" e corpo incoerente lancam (o servico captura);
 *  4. fallbackOrder: ordena por distancia Haversine crescente ao restaurante (determin.).
 */
class OsrmTripProviderTest {

    // --- 1. LON,LAT e origem fixa ---
    @Test
    fun `buildTripPath coloca a origem primeiro em LON,LAT com source=first e roundtrip=false`() {
        // Restaurante em (lat=-1.0, lng=-48.0); duas entregas.
        val path = OsrmTripProvider.buildTripPath(
            originLat = -1.0,
            originLng = -48.0,
            points = listOf(
                -1.1 to -48.1, // entrega A (lat,lng)
                -1.2 to -48.2, // entrega B
            ),
        )
        // Coords em LON,LAT separadas por ';', origem primeiro.
        assertTrue(
            path.startsWith("/trip/v1/driving/-48.0,-1.0;-48.1,-1.1;-48.2,-1.2"),
            "esperava LON,LAT com a origem primeiro; veio: $path",
        )
        assertTrue(path.contains("source=first"), "source=first ausente: $path")
        assertTrue(path.contains("roundtrip=false"), "roundtrip=false ausente: $path")
    }

    // --- 2. parse da ordem otima + totais ---
    @Test
    fun `parseTrip devolve a ordem otima dos indices de entrada e os totais`() {
        // 3 entregas. waypoints[0] = origem (index 0). A entrada [A,B,C] tem, na rota
        // otima, ordem B(1) -> C(2) -> A(3): waypoint_index A=3, B=1, C=2.
        val body = mapOf(
            "code" to "Ok",
            "waypoints" to listOf(
                mapOf("waypoint_index" to 0), // origem
                mapOf("waypoint_index" to 3), // entrada 0 (A) visita por ultimo
                mapOf("waypoint_index" to 1), // entrada 1 (B) primeiro
                mapOf("waypoint_index" to 2), // entrada 2 (C) segundo
            ),
            "trips" to listOf(mapOf("distance" to 1234.6, "duration" to 600.4)),
        )
        val result = OsrmTripProvider.parseTrip(body, pointCount = 3)
        // Ordem de visita das ENTRADAS: B(idx1), C(idx2), A(idx0).
        assertEquals(listOf(1, 2, 0), result.orderedInputIndices)
        assertEquals(1235, result.totalDistanceMeters) // arredonda HALF-UP
        assertEquals(600, result.totalDurationSeconds)
    }

    // --- 3. fail-fast ---
    @Test
    fun `parseTrip lanca quando code nao e Ok ou o corpo e incoerente`() {
        val noRoute = mapOf("code" to "NoTrips", "waypoints" to emptyList<Any>(), "trips" to emptyList<Any>())
        assertThrows(IllegalStateException::class.java) { OsrmTripProvider.parseTrip(noRoute, 2) }

        // waypoints em quantidade errada (2 entregas -> esperava 3 waypoints com a origem).
        val wrongSize = mapOf(
            "code" to "Ok",
            "waypoints" to listOf(mapOf("waypoint_index" to 0), mapOf("waypoint_index" to 1)),
            "trips" to listOf(mapOf("distance" to 10.0, "duration" to 5.0)),
        )
        assertThrows(IllegalStateException::class.java) { OsrmTripProvider.parseTrip(wrongSize, 2) }
    }

    // --- 4. fallback deterministico ---
    @Test
    fun `fallbackOrder ordena por distancia Haversine crescente ao restaurante`() {
        val a = UUID.randomUUID() // ~2 graus de lat (mais longe)
        val b = UUID.randomUUID() // ~0.5 grau (mais perto)
        val c = UUID.randomUUID() // ~1 grau (meio)
        val ordered = RouteOptimizationService.fallbackOrder(
            originLat = 0.0,
            originLng = 0.0,
            points = listOf(
                Triple(a, 2.0, 0.0),
                Triple(b, 0.5, 0.0),
                Triple(c, 1.0, 0.0),
            ),
        )
        assertEquals(listOf(b, c, a), ordered)
    }
}

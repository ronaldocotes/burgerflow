package com.menuflow.dispatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * Prova que o DistanceService (consumidor) usa a chave RESOLVIDA pelo
 * GoogleApiKeyProvider (banco > env), passando-a ao GoogleRoutesDistanceProvider por
 * requisicao — nao mais um @Value capturado no boot. Sem Spring/rede: mocks puros.
 */
class DistanceServiceKeyResolutionTest {

    @Test
    fun `provider GOOGLE usa a chave resolvida (do banco) na chamada Google`() {
        val haversine = Mockito.mock(HaversineDistanceProvider::class.java)
        val google = Mockito.mock(GoogleRoutesDistanceProvider::class.java)
        val osrm = Mockito.mock(OsrmDistanceProvider::class.java)
        val keyProvider = Mockito.mock(GoogleApiKeyProvider::class.java)

        // Simula a chave vinda do BANCO de controle.
        Mockito.`when`(keyProvider.resolve()).thenReturn("KEY-FROM-DB")
        Mockito.`when`(google.distanceMeters(-1.0, -48.0, -1.1, -48.1, "KEY-FROM-DB")).thenReturn(777L)

        val service = DistanceService(haversine, google, osrm, keyProvider, osrmBaseUrl = "")

        val meters = service.getRoadDistanceMeters(-1.0, -48.0, -1.1, -48.1, provider = "GOOGLE")

        assertEquals(777L, meters)
        // A chave que chegou no header X-Goog-Api-Key e a resolvida pelo provider.
        Mockito.verify(google).distanceMeters(-1.0, -48.0, -1.1, -48.1, "KEY-FROM-DB")
    }

    @Test
    fun `provider GOOGLE sem chave resolvida cai no Haversine`() {
        val haversine = Mockito.mock(HaversineDistanceProvider::class.java)
        val google = Mockito.mock(GoogleRoutesDistanceProvider::class.java)
        val osrm = Mockito.mock(OsrmDistanceProvider::class.java)
        val keyProvider = Mockito.mock(GoogleApiKeyProvider::class.java)

        Mockito.`when`(keyProvider.resolve()).thenReturn("") // banco e env vazios
        Mockito.`when`(haversine.distanceMeters(-1.0, -48.0, -1.1, -48.1)).thenReturn(123L)

        val service = DistanceService(haversine, google, osrm, keyProvider, osrmBaseUrl = "")

        val meters = service.getRoadDistanceMeters(-1.0, -48.0, -1.1, -48.1, provider = "GOOGLE")

        assertEquals(123L, meters)
        // Nunca chamou o Google sem chave.
        Mockito.verify(google, Mockito.never())
            .distanceMeters(Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyString())
    }
}

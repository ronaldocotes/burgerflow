package com.menuflow.platform

import com.menuflow.exception.PayloadTooLargeException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Guarda de tamanho do upload do APK (A2 — Centurião). Testa a leitura bounded
 * (AppReleaseController.readBounded) de forma pura e rapida, com um cap pequeno, para
 * NAO precisar de um corpo de 150 MB. Cobre os dois guardas contra OOM:
 *  1) Content-Length declarado acima do teto -> 413 ANTES de ler qualquer byte;
 *  2) corpo cujo tamanho real passa do teto (Content-Length ausente/mentiroso, ex.:
 *     chunked) -> 413 assim que a leitura acumulada ultrapassa o teto;
 * e o caminho feliz (corpo dentro do teto volta byte a byte).
 */
class AppReleaseControllerBoundedReadTest {

    private val cap = 1024L // 1 KiB de teto so para o teste

    @Test
    fun `Content-Length declarado acima do teto rejeita antes de ler`() {
        // Stream que EXPLODE se for lido: prova que a rejeicao acontece pelo header,
        // sem tocar o corpo.
        val exploding = object : java.io.InputStream() {
            override fun read(): Int = throw AssertionError("nao deveria ler o corpo")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw AssertionError("nao deveria ler o corpo")
        }
        assertThrows(PayloadTooLargeException::class.java) {
            AppReleaseController.readBounded(exploding, declaredLength = cap + 1, cap = cap)
        }
    }

    @Test
    fun `corpo real acima do teto sem Content-Length confiavel aborta a leitura`() {
        val big = ByteArray((cap + 512).toInt()) { 1 }
        // declaredLength = -1 simula ausencia de Content-Length (chunked).
        assertThrows(PayloadTooLargeException::class.java) {
            AppReleaseController.readBounded(ByteArrayInputStream(big), declaredLength = -1, cap = cap)
        }
    }

    @Test
    fun `corpo mentindo o Content-Length ainda e barrado pela leitura`() {
        val big = ByteArray((cap + 1).toInt()) { 7 }
        // Cliente declara caber (100 bytes) mas manda mais que o teto: guarda 2 pega.
        assertThrows(PayloadTooLargeException::class.java) {
            AppReleaseController.readBounded(ByteArrayInputStream(big), declaredLength = 100, cap = cap)
        }
    }

    @Test
    fun `corpo dentro do teto volta os bytes exatos`() {
        val payload = ByteArray(cap.toInt()) { (it % 251).toByte() }
        val out = AppReleaseController.readBounded(
            ByteArrayInputStream(payload), declaredLength = payload.size.toLong(), cap = cap,
        )
        assertArrayEquals(payload, out)
    }
}

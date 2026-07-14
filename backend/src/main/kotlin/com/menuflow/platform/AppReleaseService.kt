package com.menuflow.platform

import com.menuflow.exception.BusinessException
import com.menuflow.exception.ConflictException
import com.menuflow.exception.ResourceNotFoundException
import com.menuflow.model.control.AppRelease
import com.menuflow.repository.control.AppReleaseMetadata
import com.menuflow.repository.control.AppReleaseRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

/**
 * Regras de negocio da distribuicao do app do motoboy (banco de CONTROLE, nivel
 * plataforma). Molde SISATER, adaptado: o APK e guardado NO BANCO (BYTEA) em vez do
 * MinIO.
 *
 * Decisoes:
 *  - versionCode DUPLICADO -> 409 (ConflictException): NAO sobrescreve um release ja
 *    publicado (um app instalado que ja baixou aquela versao esperaria o mesmo binario).
 *    Para corrigir, publica-se um novo versionCode maior.
 *  - Tamanho: minimo 1 KiB (arquivo vazio/truncado -> 400) e maximo ~150 MB (-> 400).
 *  - Validacao de tipo: todo APK e um zip -> comeca com o magic "PK".
 */
@Service
class AppReleaseService(
    private val repository: AppReleaseRepository,
) {
    companion object {
        private const val MIN_BYTES = 1024                       // 1 KiB: menos que isso e lixo
        private const val MAX_BYTES = 150L * 1024 * 1024         // ~150 MB (ver max-swallow-size no application.yml)
    }

    /** Ultima versao publicada (maior version_code), SEM o binario. null = nenhuma. */
    fun latest(plataforma: String): AppReleaseMetadata? =
        repository.findFirstByPlataformaOrderByVersionCodeDesc(plataforma)

    /** Release completo (com apk_bytes) para o download. 404 se nao existir. */
    fun forDownload(plataforma: String, versionCode: Int): AppRelease =
        repository.findFirstByPlataformaAndVersionCode(plataforma, versionCode)
            ?: throw ResourceNotFoundException("Versao $versionCode ($plataforma) nao encontrada")

    /**
     * Publica uma nova versao. Valida tamanho e o magic "PK", rejeita versionCode
     * duplicado (409), calcula o sha256 e persiste o binario.
     */
    @Transactional
    fun publish(
        plataforma: String,
        versionCode: Int,
        versionName: String,
        notas: String?,
        obrigatoria: Boolean,
        apk: ByteArray,
    ): AppRelease {
        if (apk.size < MIN_BYTES) {
            throw BusinessException("APK vazio ou muito pequeno (minimo ${MIN_BYTES} bytes)")
        }
        if (apk.size.toLong() > MAX_BYTES) {
            throw BusinessException("APK excede o limite de ${MAX_BYTES / (1024 * 1024)} MB")
        }
        // Todo APK e um zip -> os dois primeiros bytes sao o magic "PK" (0x50 0x4B).
        if (apk[0] != 'P'.code.toByte() || apk[1] != 'K'.code.toByte()) {
            throw BusinessException("O arquivo nao parece um APK (assinatura zip 'PK' ausente)")
        }
        if (repository.existsByPlataformaAndVersionCode(plataforma, versionCode)) {
            throw ConflictException("versionCode $versionCode ($plataforma) ja publicado")
        }

        return repository.save(
            AppRelease(
                plataforma = plataforma,
                versionCode = versionCode,
                versionName = versionName,
                notas = notas?.takeIf { it.isNotBlank() },
                obrigatoria = obrigatoria,
                apkBytes = apk,
                tamanhoBytes = apk.size.toLong(),
                sha256 = sha256(apk),
            ),
        )
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}

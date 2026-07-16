package com.menuflow.platform

import com.menuflow.exception.PayloadTooLargeException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration

/**
 * Distribuicao/atualizacao do app do motoboy FORA da Play Store (self-hospedado).
 * Molde SISATER (AppReleaseController), adaptado ao MenuFlow: o APK fica no BANCO de
 * CONTROLE (BYTEA), nao no MinIO.
 *
 * Rotas (relativas ao context-path /api/v1):
 *  - GET  /public/app/latest              -> ultima versao (metadados, sem binario). PUBLICO.
 *  - GET  /public/app/download/{code}     -> stream do APK. PUBLICO.
 *  - POST /admin/app/releases             -> publica uma versao. So SUPER_ADMIN.
 *
 * Seguranca:
 *  - as rotas sob /public sao permitAll no SecurityConfig (o app baixa mesmo deslogado;
 *    o APK e distribuivel). O download NUNCA expoe nada alem do proprio binario.
 *  - as rotas sob /admin exigem SUPER_ADMIN no matcher path-level (cinta) + @PreAuthorize
 *    aqui (suspensorio) — defesa em profundidade, mesmo padrao do PlatformApiKeyController.
 *  - GET /latest NUNCA inclui o binario (usa projecao de metadados).
 */
@RestController
class AppReleaseController(
    private val service: AppReleaseService,
) {
    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val CONTEXT_PATH = "/api/v1"

        /**
         * Le [input] para memoria com teto rigido [cap], rejeitando CEDO. Dois guardas
         * contra OOM (extraido para ser testavel com um cap pequeno):
         *  1) Se o Content-Length [declaredLength] ja excede o teto -> 413 ANTES de ler
         *     qualquer byte.
         *  2) Corpo sem/ com Content-Length mentiroso (ex.: chunked): leitura em blocos
         *     que ABORTA com 413 assim que o acumulado passa do teto — nunca bufferiza
         *     alem dele.
         */
        internal fun readBounded(input: InputStream, declaredLength: Long, cap: Long): ByteArray {
            val mb = cap / (1024 * 1024)
            if (declaredLength > cap) {
                throw PayloadTooLargeException(
                    "Upload de $declaredLength bytes excede o limite de $mb MB",
                )
            }
            val initial = if (declaredLength in 1..cap) declaredLength.toInt() else 64 * 1024
            val out = ByteArrayOutputStream(initial)
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            input.use { stream ->
                while (true) {
                    val n = stream.read(chunk)
                    if (n == -1) break
                    total += n
                    if (total > cap) {
                        throw PayloadTooLargeException("Upload excede o limite de $mb MB")
                    }
                    out.write(chunk, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    /**
     * Ultima versao publicada de uma plataforma (default android). O app compara o
     * versionCode retornado com o instalado. 204 No Content se ainda nao ha release.
     * NUNCA inclui o binario.
     */
    @GetMapping("/public/app/latest")
    fun latest(
        @RequestParam(defaultValue = "android") plataforma: String,
    ): ResponseEntity<Map<String, Any?>> {
        val r = service.latest(plataforma) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(
            linkedMapOf(
                "versionCode" to r.versionCode,
                "versionName" to r.versionName,
                "notas" to r.notas,
                "obrigatoria" to r.obrigatoria,
                "tamanhoBytes" to r.tamanhoBytes,
                "sha256" to r.sha256,
                "url" to "$CONTEXT_PATH/public/app/download/${r.versionCode}?plataforma=$plataforma",
            ),
        )
    }

    /**
     * Stream do APK de uma versao especifica. Content-Type de APK, download como anexo
     * com o nome menuflow-motoboy-<versionName>.apk e Content-Length. 404 se nao existir.
     *
     * O APK de um versionCode e IMUTAVEL (publicar duplicado da 409, nunca sobrescreve),
     * entao o binario pode ser cacheado agressivamente: Cache-Control public + max-age de
     * 1 ano + immutable (o navegador nem revalida) e um ETag = sha256 do arquivo (para
     * CDN/proxy que queiram revalidar por If-None-Match). Assim o mesmo APK nao e
     * re-baixado a toa por navegador/CDN.
     */
    @GetMapping("/public/app/download/{versionCode}")
    fun download(
        @PathVariable versionCode: Int,
        @RequestParam(defaultValue = "android") plataforma: String,
    ): ResponseEntity<ByteArray> {
        val release = service.forDownload(plataforma, versionCode)
        val filename = "menuflow-motoboy-${release.versionName}.apk"
        val builder = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(APK_MIME))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString(),
            )
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .contentLength(release.apkBytes.size.toLong())
        // ETag = sha256 (forte, imutavel por versionCode). eTag() ja envolve em aspas.
        release.sha256?.let { builder.eTag("\"$it\"") }
        return builder.body(release.apkBytes)
    }

    /**
     * Publica uma nova versao. O APK vai no CORPO como octet-stream (nao multipart) e
     * os metadados em query params, para nao esbarrar no limite de multipart. Ex.:
     * {@code curl -X POST ".../admin/app/releases?versionCode=2&versionName=1.1.0" \
     *        -H "Authorization: Bearer <token-super-admin>" \
     *        -H "Content-Type: application/octet-stream" --data-binary @app.apk}
     *
     * versionCode duplicado -> 409; arquivo nao-APK / vazio -> 400; corpo maior que o
     * teto -> 413 (rejeitado CEDO, sem bufferizar o corpo inteiro).
     *
     * O corpo NAO e lido via @RequestBody (que bufferizaria tudo antes do metodo);
     * lemos o stream nós mesmos com [readBoundedApkBody], que rejeita pelo
     * Content-Length declarado e trava a leitura no teto — para um corpo gigante
     * (honesto ou mentiroso) nunca virar OOM.
     */
    @PostMapping("/admin/app/releases", consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun publish(
        @RequestParam versionCode: Int,
        @RequestParam versionName: String,
        @RequestParam(defaultValue = "android") plataforma: String,
        @RequestParam(required = false) notas: String?,
        @RequestParam(defaultValue = "false") obrigatoria: Boolean,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val apk = readBoundedApkBody(request)
        val r = service.publish(plataforma, versionCode, versionName, notas, obrigatoria, apk)
        return linkedMapOf(
            "ok" to true,
            "versionCode" to r.versionCode,
            "versionName" to r.versionName,
            "plataforma" to r.plataforma,
            "obrigatoria" to r.obrigatoria,
            "tamanhoBytes" to r.tamanhoBytes,
            "sha256" to r.sha256,
        )
    }

    /**
     * Le o corpo octet-stream do request com teto rigido, sem bufferizar alem dele.
     * A validacao fina (magic PK, tamanho minimo, duplicata) continua no service.
     */
    private fun readBoundedApkBody(request: HttpServletRequest): ByteArray =
        readBounded(request.inputStream, request.contentLengthLong, AppReleaseService.MAX_UPLOAD_BYTES)
}

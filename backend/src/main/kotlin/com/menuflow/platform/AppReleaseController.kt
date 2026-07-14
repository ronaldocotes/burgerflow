package com.menuflow.platform

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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
     */
    @GetMapping("/public/app/download/{versionCode}")
    fun download(
        @PathVariable versionCode: Int,
        @RequestParam(defaultValue = "android") plataforma: String,
    ): ResponseEntity<ByteArray> {
        val release = service.forDownload(plataforma, versionCode)
        val filename = "menuflow-motoboy-${release.versionName}.apk"
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(APK_MIME))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString(),
            )
            .contentLength(release.apkBytes.size.toLong())
            .body(release.apkBytes)
    }

    /**
     * Publica uma nova versao. O APK vai no CORPO como octet-stream (nao multipart) e
     * os metadados em query params, para nao esbarrar no limite de multipart. Ex.:
     * {@code curl -X POST ".../admin/app/releases?versionCode=2&versionName=1.1.0" \
     *        -H "Authorization: Bearer <token-super-admin>" \
     *        -H "Content-Type: application/octet-stream" --data-binary @app.apk}
     *
     * versionCode duplicado -> 409; arquivo nao-APK / vazio / grande demais -> 400.
     */
    @PostMapping("/admin/app/releases", consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun publish(
        @RequestParam versionCode: Int,
        @RequestParam versionName: String,
        @RequestParam(defaultValue = "android") plataforma: String,
        @RequestParam(required = false) notas: String?,
        @RequestParam(defaultValue = "false") obrigatoria: Boolean,
        @RequestBody apk: ByteArray,
    ): Map<String, Any?> {
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
}

package com.menuflow.repository.control

import com.menuflow.model.control.AppRelease
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Repositorio (banco de CONTROLE) das versoes publicadas do app do motoboy.
 *
 * IMPORTANTE: o GET /public/app/latest usa [AppReleaseMetadata] (projecao fechada) —
 * o Spring Data seleciona SO as colunas de metadados, NUNCA a coluna apk_bytes (BYTEA).
 * Assim a listagem/consulta da ultima versao nao arrasta o binario para a memoria. O
 * binario so e carregado no download, por [findFirstByPlataformaAndVersionCode].
 */
@Repository
interface AppReleaseRepository : JpaRepository<AppRelease, Long> {

    /** Ultima versao (maior version_code) de uma plataforma, SEM o binario (projecao). */
    fun findFirstByPlataformaOrderByVersionCodeDesc(plataforma: String): AppReleaseMetadata?

    /** Release completo (inclui apk_bytes) de uma versao especifica — para o download. */
    fun findFirstByPlataformaAndVersionCode(plataforma: String, versionCode: Int): AppRelease?

    /** Ja existe esse (plataforma, versionCode)? Usado para rejeitar duplicata (409). */
    fun existsByPlataformaAndVersionCode(plataforma: String, versionCode: Int): Boolean
}

/**
 * Projecao fechada de METADADOS (sem o binario). O Spring Data JPA gera um SELECT
 * apenas das colunas abaixo, entao o /latest nunca carrega apk_bytes.
 */
interface AppReleaseMetadata {
    val versionCode: Int
    val versionName: String
    val notas: String?
    val obrigatoria: Boolean
    val tamanhoBytes: Long
    val sha256: String?
}

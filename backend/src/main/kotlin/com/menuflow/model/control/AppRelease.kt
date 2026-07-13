package com.menuflow.model.control

import jakarta.persistence.*
import java.time.Instant

/**
 * Versao publicada do app mobile (app do motoboy), para a distribuicao/atualizacao
 * self-hospedada FORA da Play Store. Nivel PLATAFORMA (banco de CONTROLE, SEM escopo
 * de tenant): o APK e o mesmo para todos os restaurantes.
 *
 * Diferente do molde SISATER (que guarda o APK no MinIO e so a chave no banco), aqui
 * o binario inteiro fica em [apkBytes] (BYTEA) — o MenuFlow nao tem object storage.
 *
 * Espelha db/control/migration/V17__app_release.sql. Hibernate VALIDA (nunca altera)
 * o schema do banco de controle no boot, entao os tipos precisam bater.
 *
 * Invariante: no maximo UMA linha por (plataforma, version_code), garantida no banco
 * pelo UNIQUE uq_app_release_plataforma_version. A publicacao rejeita duplicata com
 * 409 antes de chegar ao banco.
 */
@Entity
@Table(name = "app_release")
class AppRelease(
    @Column(nullable = false, length = 16)
    var plataforma: String = "android",

    @Column(name = "version_code", nullable = false)
    var versionCode: Int,

    @Column(name = "version_name", nullable = false, length = 40)
    var versionName: String,

    @Column(columnDefinition = "text")
    var notas: String? = null,

    @Column(nullable = false)
    var obrigatoria: Boolean = false,

    // O APK inteiro. So e carregado no download (findFirstByPlataformaAndVersionCode);
    // o /latest usa uma projecao que NAO seleciona esta coluna (nao arrasta o binario).
    @Column(name = "apk_bytes", nullable = false)
    var apkBytes: ByteArray,

    @Column(name = "tamanho_bytes", nullable = false)
    var tamanhoBytes: Long,

    @Column(length = 64)
    var sha256: String? = null,

    @Column(name = "criado_em", nullable = false)
    var criadoEm: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)

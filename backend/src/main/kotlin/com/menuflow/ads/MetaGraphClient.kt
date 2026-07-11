package com.menuflow.ads

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate

/**
 * Cliente minimo da Meta Graph API para a Fase 8.0 (read-only). So conhece um
 * endpoint: GET /me/adaccounts, usado para VALIDAR o System User Token colado pelo
 * restaurante e descobrir quais contas de anuncio ele controla.
 *
 * HTTP via JDK HttpClient (timeout connect+read), mesmo padrao simples do
 * PlatformIntegrationsHealthService — sem RestClient/dependencia extra.
 *
 * Versao da Graph API fixada em v21.0 (config meta.graph.api-version): a Meta
 * exige versao explicita na URL e deprecia versoes antigas; fixar evita quebra
 * silenciosa quando o "default" da Meta avanca. Bump e mecanico.
 *
 * Seguranca: o token vai no header Authorization: Bearer (nunca na query string, que
 * vaza em logs/proxies). NUNCA logar o token nem o corpo bruto de erro cru.
 */
@Component
class MetaGraphClient(
    private val objectMapper: ObjectMapper,
    @Value("\${meta.graph.base-url:https://graph.facebook.com}") private val baseUrl: String,
    @Value("\${meta.graph.api-version:v21.0}") private val apiVersion: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /**
     * Lista as contas de anuncio que o token controla. Valida o token de quebra:
     *  - 2xx -> parseia data[] em [MetaAdAccountDto];
     *  - erro com code 190 (OAuthException) -> [MetaTokenInvalidException];
     *  - qualquer outra falha (rede/timeout/5xx/erro Graph) -> [MetaGraphException].
     */
    fun fetchAdAccounts(accessToken: String): List<MetaAdAccountDto> {
        val fields = URLEncoder.encode("account_id,name,currency,timezone_name", StandardCharsets.UTF_8)
        val url = "$baseUrl/$apiVersion/me/adaccounts?fields=$fields"

        val response = try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer $accessToken")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("[meta-graph] falha de rede ao chamar /me/adaccounts: {}", e.message)
            throw MetaGraphException("Nao foi possivel contatar a Meta: ${e.message}")
        }

        val root: JsonNode = try {
            objectMapper.readTree(response.body())
        } catch (e: Exception) {
            throw MetaGraphException("Resposta invalida da Meta (HTTP ${response.statusCode()})")
        }

        if (response.statusCode() !in 200..299) {
            val error = root.get("error")
            val code = error?.get("code")?.asInt()
            val message = error?.get("message")?.asText() ?: "HTTP ${response.statusCode()}"
            // 190 = token invalido/expirado/revogado (OAuthException). Distinto para o
            // service devolver um 400 claro ("reconecte") em vez de um 503 generico.
            if (code == 190) throw MetaTokenInvalidException(message)
            log.warn("[meta-graph] erro Graph code={} status={}", code, response.statusCode())
            throw MetaGraphException("A Meta recusou a requisicao: $message")
        }

        val data = root.get("data") ?: return emptyList()
        return data.mapNotNull { node ->
            val externalId = node.get("account_id")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MetaAdAccountDto(
                externalAccountId = externalId,
                name = node.get("name")?.asText(),
                currency = node.get("currency")?.asText(),
                timezoneName = node.get("timezone_name")?.asText(),
            )
        }
    }

    /**
     * Insights DIARIOS a nivel de CONTA (Fase 8.1). Chama
     * GET /act_{id}/insights?level=account&time_increment=1&time_range={since,until}
     * e devolve UMA linha por dia do intervalo (por causa de time_increment=1). Agrega
     * TODAS as campanhas da conta — inclusive as que o restaurante ja roda direto no Meta
     * Ads Manager (o valor desta fase, antes de existir campanha propria).
     *
     * Tratamento de erro (mesmo espirito do fetchAdAccounts):
     *  - code 190 -> [MetaTokenInvalidException] (o service marca a conta EXPIRED);
     *  - rate-limit (code 613, subcode 80004, ou HTTP 429) -> [MetaRateLimitException]
     *    (o job so PULA aquela conta neste tick, sem derrubar a varredura);
     *  - rede/timeout/5xx/erro Graph -> [MetaGraphException].
     *
     * Conta sem gasto no periodo devolve data:[] -> lista vazia (zero, nao erro).
     *
     * Dinheiro: spend/cpc vem como string decimal na moeda da conta -> convertidos para
     * centavos com BigDecimal (HALF_UP), NUNCA float. ctr vem como string percentual ->
     * guardado como ctr_milli (CTR% * 1000, ex.: "1.5" -> 1500).
     */
    fun fetchAccountInsights(
        accessToken: String,
        externalAccountId: String,
        since: LocalDate,
        until: LocalDate,
    ): List<MetaInsightDto> {
        val fields = URLEncoder.encode("spend,impressions,reach,clicks,ctr,cpc", StandardCharsets.UTF_8)
        // externalAccountId e guardado SEM o prefixo (AdAccount.kt / V58); a Graph API
        // exige "act_" no path do node.
        val actId = if (externalAccountId.startsWith("act_")) externalAccountId else "act_$externalAccountId"
        val timeRangeJson = """{"since":"$since","until":"$until"}"""
        val timeRange = URLEncoder.encode(timeRangeJson, StandardCharsets.UTF_8)
        val url = "$baseUrl/$apiVersion/$actId/insights" +
            "?level=account&time_increment=1&fields=$fields&time_range=$timeRange"

        val response = try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer $accessToken")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("[meta-graph] falha de rede ao chamar /insights: {}", e.message)
            throw MetaGraphException("Nao foi possivel contatar a Meta: ${e.message}")
        }

        val root: JsonNode = try {
            objectMapper.readTree(response.body())
        } catch (e: Exception) {
            throw MetaGraphException("Resposta invalida da Meta (HTTP ${response.statusCode()})")
        }

        if (response.statusCode() !in 200..299) {
            val error = root.get("error")
            val code = error?.get("code")?.asInt()
            val subcode = error?.get("error_subcode")?.asInt()
            val message = error?.get("message")?.asText() ?: "HTTP ${response.statusCode()}"
            if (code == 190) throw MetaTokenInvalidException(message)
            // Rate-limit / Business Use Case throttling: nao e erro do usuario, e transitorio.
            // Pular so esta conta neste tick (o job trata) evita estourar a varredura.
            if (code == 613 || subcode == 80004 || response.statusCode() == 429) {
                log.warn("[meta-graph] rate-limit em /insights code={} subcode={}", code, subcode)
                throw MetaRateLimitException("Meta limitou a taxa de chamadas: $message")
            }
            log.warn("[meta-graph] erro Graph /insights code={} status={}", code, response.statusCode())
            throw MetaGraphException("A Meta recusou a requisicao: $message")
        }

        val data = root.get("data") ?: return emptyList()
        return data.mapNotNull { node ->
            val date = node.get("date_start")?.asText()?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            MetaInsightDto(
                date = date,
                spendCents = toCents(node.get("spend")?.asText()),
                impressions = toLong(node.get("impressions")?.asText()),
                reach = toLong(node.get("reach")?.asText()),
                clicks = toLong(node.get("clicks")?.asText()),
                ctrMilli = toMilli(node.get("ctr")?.asText()),
                cpcCents = toCents(node.get("cpc")?.asText()),
            )
        }
    }

    /** "12.34" -> 1234 centavos (HALF_UP). null/vazio/malformado -> 0. Nunca float. */
    private fun toCents(raw: String?): Long =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() }.getOrNull() }
            ?: 0L

    /** "1.5" (CTR%) -> 1500 (CTR% * 1000, HALF_UP). null/vazio/malformado -> 0. */
    private fun toMilli(raw: String?): Int =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it).movePointRight(3).setScale(0, RoundingMode.HALF_UP).toInt() }.getOrNull() }
            ?: 0

    /** Inteiros (impressions/reach/clicks) que a Meta manda como string. Malformado -> 0. */
    private fun toLong(raw: String?): Long =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it).setScale(0, RoundingMode.HALF_UP).toLong() }.getOrNull() }
            ?: 0L

    // ---------------------------------------------------------------------------------------
    // Fase 8.2 — ESCRITAS que criam/pausam/ativam campanha (GASTAM DINHEIRO REAL).
    // Todas com o mesmo tratamento de erro do read (190 -> token invalido; 613/80004/429 ->
    // rate-limit; resto -> MetaGraphException). Token SEMPRE no header Bearer, nunca na URL.
    // ---------------------------------------------------------------------------------------

    /**
     * Paginas do Facebook que o token administra (GET /me/accounts). Necessario porque criar
     * criativo exige um page_id (object_story_spec). Lista vazia => o token nao administra
     * nenhuma Pagina (o restaurante precisa conectar uma Pagina ao Business Manager).
     */
    fun fetchPages(accessToken: String): List<MetaPageDto> {
        val fields = URLEncoder.encode("id,name", StandardCharsets.UTF_8)
        val url = "$baseUrl/$apiVersion/me/accounts?fields=$fields"
        val req = authGet(accessToken, url)
        val root = exchange(req, "GET me/accounts")
        val data = root.get("data") ?: return emptyList()
        return data.mapNotNull { node ->
            val id = node.get("id")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MetaPageDto(id = id, name = node.get("name")?.asText())
        }
    }

    /** Cria a campanha (nasce PAUSED). special_ad_categories=[] e obrigatorio mesmo vazio. */
    fun createCampaign(accessToken: String, externalAccountId: String, name: String, objective: String): String {
        val root = postForm(
            accessToken, "${actNode(externalAccountId)}/campaigns",
            mapOf(
                "name" to name,
                "objective" to objective,
                "special_ad_categories" to "[]",
                "status" to "PAUSED",
            ),
        )
        return requireId(root, "campanha")
    }

    /**
     * Cria o conjunto de anuncios (adset). daily_budget em CENTAVOS da moeda DA CONTA.
     * Segmentacao geografica por raio (custom_locations, radius em km [1..80]).
     *
     * SEGURANCA MONETARIA: nasce status=PAUSED (nao ACTIVE). Nao dependemos da PREMISSA de que
     * filho ACTIVE sob campanha PAUSED nao roda/cobra (nao validada em conta real): com o adset
     * TAMBEM PAUSED, nada entrega por CONSTRUCAO, mesmo que a hierarquia da Meta se comporte de
     * outra forma. A ativacao (AdCampaignService.activate) flipa adset+ad+campanha para ACTIVE.
     * (Comportamento fino da hierarquia ainda deve ser confirmado em conta real, mas o risco de
     * spend acidental aqui e zero por construcao.)
     */
    fun createAdSet(
        accessToken: String,
        externalAccountId: String,
        campaignId: String,
        name: String,
        dailyBudgetCents: Long,
        lat: Double,
        lng: Double,
        radiusKm: Int,
    ): String {
        val targeting =
            """{"geo_locations":{"custom_locations":[{"latitude":$lat,"longitude":$lng,"radius":$radiusKm,"distance_unit":"kilometer"}]}}"""
        val root = postForm(
            accessToken, "${actNode(externalAccountId)}/adsets",
            mapOf(
                "name" to name,
                "campaign_id" to campaignId,
                "daily_budget" to dailyBudgetCents.toString(),
                "billing_event" to "IMPRESSIONS",
                "optimization_goal" to "LINK_CLICKS",
                "bid_strategy" to "LOWEST_COST_WITHOUT_CAP",
                "targeting" to targeting,
                "status" to "PAUSED",
            ),
        )
        return requireId(root, "adset")
    }

    /**
     * Baixa a imagem da [imageUrl] (foto do catalogo) e sobe em /act_{id}/adimages via
     * multipart, devolvendo o image_hash. A resposta da Meta e
     * {"images":{"<arquivo>":{"hash":"...","url":"..."}}}.
     */
    fun uploadAdImage(accessToken: String, externalAccountId: String, imageUrl: String): String {
        val bytes = downloadBytes(imageUrl)
        val boundary = "----menuflow${System.nanoTime()}"
        val body = multipartSingleFile(boundary, "filename", "menuflow_ad.jpg", "image/jpeg", bytes)
        val url = "$baseUrl/$apiVersion/${actNode(externalAccountId)}/adimages"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val root = exchange(req, "POST adimages")
        val images = root.get("images") ?: throw MetaGraphException("Upload de imagem sem 'images' na resposta")
        val first = images.fields().asSequence().firstOrNull()?.value
            ?: throw MetaGraphException("Upload de imagem devolveu vazio")
        return first.get("hash")?.asText()?.takeIf { it.isNotBlank() }
            ?: throw MetaGraphException("Upload de imagem sem hash")
    }

    /**
     * Cria o criativo (object_story_spec com page_id + link_data{message,link,image_hash?}).
     * [imageHash] nulo => criativo link-only (a Meta PODE recusar; validar com conta real).
     */
    fun createAdCreative(
        accessToken: String,
        externalAccountId: String,
        pageId: String,
        name: String,
        message: String,
        link: String,
        imageHash: String?,
    ): String {
        val linkData = buildString {
            append("""{"message":${jsonStr(message)},"link":${jsonStr(link)}""")
            if (!imageHash.isNullOrBlank()) append(""","image_hash":${jsonStr(imageHash)}""")
            append("}")
        }
        val storySpec = """{"page_id":${jsonStr(pageId)},"link_data":$linkData}"""
        val root = postForm(
            accessToken, "${actNode(externalAccountId)}/adcreatives",
            mapOf("name" to name, "object_story_spec" to storySpec),
        )
        return requireId(root, "criativo")
    }

    /**
     * Cria o anuncio (ad) ligando adset + creative. Nasce status=PAUSED (mesma razao do adset):
     * nada entrega por construcao ate a ativacao explicita flipar os tres (adset+ad+campanha).
     */
    fun createAd(
        accessToken: String,
        externalAccountId: String,
        name: String,
        adsetId: String,
        creativeId: String,
    ): String {
        val creative = """{"creative_id":${jsonStr(creativeId)}}"""
        val root = postForm(
            accessToken, "${actNode(externalAccountId)}/ads",
            mapOf("name" to name, "adset_id" to adsetId, "creative" to creative, "status" to "PAUSED"),
        )
        return requireId(root, "ad")
    }

    /** Pausa/ativa um objeto (usado na CAMPANHA): POST /{id} status=PAUSED|ACTIVE. */
    fun updateStatus(accessToken: String, objectId: String, status: String) {
        postForm(accessToken, objectId, mapOf("status" to status))
    }

    /** effective_status atual do objeto na Meta (espelho para a UI). Nulo se ausente. */
    fun fetchCampaignEffectiveStatus(accessToken: String, objectId: String): String? {
        val url = "$baseUrl/$apiVersion/$objectId?fields=effective_status"
        val root = exchange(authGet(accessToken, url), "GET effective_status")
        return root.get("effective_status")?.asText()?.takeIf { it.isNotBlank() }
    }

    /**
     * DELETE /{id} — compensacao de falha parcial na saga de criacao: apagar a campanha na
     * Meta cascateia adset/ad/creative, evitando lixo na conta do cliente. Best-effort do
     * ponto de vista do chamador (que ja esta tratando um erro).
     */
    fun deleteObject(accessToken: String, objectId: String) {
        val url = "$baseUrl/$apiVersion/$objectId"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $accessToken")
            .timeout(Duration.ofSeconds(15))
            .DELETE()
            .build()
        exchange(req, "DELETE $objectId")
    }

    // --- helpers de escrita ---------------------------------------------------------------

    /** Prefixa "act_" no id da conta para o path do node (o id e guardado sem o prefixo). */
    private fun actNode(externalAccountId: String): String =
        if (externalAccountId.startsWith("act_")) externalAccountId else "act_$externalAccountId"

    private fun authGet(accessToken: String, url: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $accessToken")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

    /** POST form-urlencoded a um node do Graph; devolve o JSON 2xx (lanca nos erros Graph). */
    private fun postForm(accessToken: String, node: String, params: Map<String, String>): JsonNode {
        val form = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, StandardCharsets.UTF_8)}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}"
        }
        val url = "$baseUrl/$apiVersion/$node"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        return exchange(req, "POST $node")
    }

    /** Envia a requisicao, parseia o corpo e traduz erros Graph em excecoes tipadas. */
    private fun exchange(req: HttpRequest, opName: String): JsonNode {
        val response = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("[meta-graph] falha de rede em {}: {}", opName, e.message)
            throw MetaGraphException("Nao foi possivel contatar a Meta: ${e.message}")
        }
        val root: JsonNode = try {
            objectMapper.readTree(response.body())
        } catch (e: Exception) {
            throw MetaGraphException("Resposta invalida da Meta (HTTP ${response.statusCode()})")
        }
        if (response.statusCode() !in 200..299) throwGraphError(root, response.statusCode(), opName)
        return root
    }

    private fun throwGraphError(root: JsonNode, status: Int, opName: String): Nothing {
        val error = root.get("error")
        val code = error?.get("code")?.asInt()
        val subcode = error?.get("error_subcode")?.asInt()
        val message = error?.get("message")?.asText() ?: "HTTP $status"
        if (code == 190) throw MetaTokenInvalidException(message)
        if (code == 613 || subcode == 80004 || status == 429) {
            log.warn("[meta-graph] rate-limit em {} code={} subcode={}", opName, code, subcode)
            throw MetaRateLimitException("Meta limitou a taxa de chamadas: $message")
        }
        log.warn("[meta-graph] erro Graph {} code={} status={}", opName, code, status)
        throw MetaGraphException("A Meta recusou a requisicao: $message")
    }

    private fun requireId(root: JsonNode, what: String): String =
        root.get("id")?.asText()?.takeIf { it.isNotBlank() }
            ?: throw MetaGraphException("A Meta nao devolveu id ao criar $what")

    /** Escapa uma string para JSON com aspas (para montar object_story_spec/targeting). */
    private fun jsonStr(s: String): String = objectMapper.writeValueAsString(s)

    private fun downloadBytes(url: String): ByteArray {
        // Guard anti-SSRF (defesa em profundidade — o AdCampaignService ja valida antes da saga).
        // https-only + IP resolvido nao pode ser interno/privado. Lanca ANTES de qualquer I/O:
        // uma URL insegura NUNCA chega a virar requisicao de rede.
        assertSafeImageUrl(url)
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Exception) {
            throw MetaGraphException("Nao foi possivel baixar a imagem do anuncio: ${e.message}")
        }
        if (resp.statusCode() !in 200..299) {
            throw MetaGraphException("Falha ao baixar a imagem do anuncio (HTTP ${resp.statusCode()})")
        }
        return resp.body()
    }

    /** Monta um corpo multipart/form-data com um unico arquivo binario. */
    private fun multipartSingleFile(
        boundary: String,
        fieldName: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n" +
            "Content-Type: $contentType\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    companion object {
        /**
         * Guard anti-SSRF para a URL da imagem do anuncio. A `imageUrl` vem do catalogo
         * (Product.imageUrl) e e STRING LIVRE do cliente (ProductDtos/ProductService, sem
         * validacao de scheme/host) — um ADMIN/MANAGER poderia apontar para
         * http://169.254.169.254/... (metadata da nuvem) ou um servico interno da A1. Como nao
         * ha um host/CDN de imagem conhecido para montar allowlist, usamos blocklist + https-only.
         *
         * Rejeita (IllegalArgumentException — o chamador traduz para 400) ANTES de qualquer
         * download:
         *  - scheme != https  -> bloqueia http/file/gopher/dict/ftp e SSRF por protocolo;
         *  - host que RESOLVE para IP interno/privado/loopback/link-local:
         *    127/8, 10/8, 172.16/12, 192.168/16, 169.254/16, 0.0.0.0, ::1, fe80::/10, fc00::/7,
         *    multicast. Valida TODOS os IPs resolvidos (nao so o hostname) para mitigar DNS
         *    rebinding — se qualquer registro A/AAAA cair em faixa interna, recusa.
         */
        fun assertSafeImageUrl(rawUrl: String) {
            val uri = try {
                URI.create(rawUrl.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("URL de imagem invalida.")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https") {
                throw IllegalArgumentException("URL de imagem deve usar https (recebido: ${scheme ?: "sem scheme"}).")
            }
            val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("URL de imagem sem host valido.")
            val addresses = try {
                InetAddress.getAllByName(host)
            } catch (e: UnknownHostException) {
                throw IllegalArgumentException("Nao foi possivel resolver o host da imagem.")
            }
            if (addresses.isEmpty()) {
                throw IllegalArgumentException("Host da imagem nao resolveu para nenhum IP.")
            }
            for (addr in addresses) {
                if (isBlockedAddress(addr)) {
                    throw IllegalArgumentException(
                        "URL de imagem aponta para um endereco interno/privado, o que nao e permitido.",
                    )
                }
            }
        }

        /** IP que NAO pode ser alvo de download (faixas internas/privadas/especiais). */
        private fun isBlockedAddress(addr: InetAddress): Boolean =
            addr.isLoopbackAddress ||        // 127/8, ::1
                addr.isAnyLocalAddress ||    // 0.0.0.0, ::
                addr.isLinkLocalAddress ||   // 169.254/16, fe80::/10
                addr.isSiteLocalAddress ||   // 10/8, 172.16/12, 192.168/16
                addr.isMulticastAddress ||
                // ULA IPv6 fc00::/7 (nao coberto por isSiteLocalAddress).
                (addr is Inet6Address && ((addr.address.firstOrNull()?.toInt() ?: 0) and 0xfe) == 0xfc)
    }
}

/** Pagina do Facebook que o token administra (GET /me/accounts). page_id p/ o criativo. */
data class MetaPageDto(
    val id: String,
    val name: String?,
)

/** Conta de anuncio como a Meta a devolve (subset dos campos pedidos). */
data class MetaAdAccountDto(
    val externalAccountId: String,
    val name: String?,
    val currency: String?,
    val timezoneName: String?,
)

/**
 * Uma linha diaria de insights (nivel conta) ja normalizada: dinheiro em centavos na
 * moeda da conta, CTR em milesimos de ponto percentual. Sem float.
 */
data class MetaInsightDto(
    val date: LocalDate,
    val spendCents: Long,
    val impressions: Long,
    val reach: Long,
    val clicks: Long,
    val ctrMilli: Int,
    val cpcCents: Long,
)

package com.fenix.ia.research

import com.fenix.ia.tools.ToolResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Buscador y scraper web sin API keys de pago.
 *
 * FIX F3 — Tres problemas corregidos:
 *
 * Bug 3a — Regex catastrófico sobre HTML completo de 200 KB+:
 *   El RESULT_BLOCK regex con DOT_MATCHES_ALL sobre el body completo de DDG
 *   producía OutOfMemoryError o backtracking exponencial en dispositivos con poca RAM.
 *   FIX: se usa la API JSON de DuckDuckGo Instant Answers (api.duckduckgo.com?format=json)
 *   que devuelve JSON liviano con RelatedTopics — sin regex, sin HTML, sin OOM.
 *   Fallback al endpoint HTML solo si la API JSON devuelve 0 resultados (consultas complejas).
 *
 * Bug 3b — socketTimeoutMillis = 120s en cliente compartido:
 *   DDG desde Argentina puede superar 120s en condiciones de red adversa.
 *   FIX: WebResearcher inyecta @Named("api") que tiene socketTimeout = 90s
 *   (valor calibrado — suficiente para DDG sin ser indefinido).
 *
 * Bug 3c — Sin User-Agent causa bloqueo 403 en sitios modernos:
 *   Algunos sitios bloquean requests sin UA real. Se mantiene UA de Chrome 120.
 *
 * Estrategia de búsqueda:
 *   1. DDG Instant Answer API (JSON) — liviana, estructurada, sin parsing HTML
 *   2. Si RelatedTopics vacío → fallback DDG HTML con regex ACOTADO por bloque
 *   3. Scraping: extracción de texto con procesamiento de HTML stdlib Kotlin
 */
@Singleton
class WebResearcher @Inject constructor(
    @Named("api") private val httpClient: HttpClient   // FIX 3b: cliente con socket 90s
) {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // FIX 3a: API JSON en lugar del endpoint HTML que requería regex sobre 200 KB
        private const val DDG_JSON_URL = "https://api.duckduckgo.com/"
        private const val DDG_HTML_URL = "https://html.duckduckgo.com/html/"

        private const val MAX_CONTENT  = 8_000
        private const val MAX_RESULTS  = 10

        // Regex ACOTADOS — operan sobre bloques pequeños, no sobre el HTML completo
        // Solo se usan en el fallback HTML
        private val LINK_HREF  = Regex("""href="(https?://[^"]+)"""")
        private val STRIP_TAGS = Regex("""<[^>]{1,200}>""")    // límite de 200 chars por tag

        // Para scraping de páginas
        private val SCRIPT_STYLE  = Regex(
            """<(script|style|nav|footer|header)[^>]{0,200}>.*?</\1>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        private val BLOCK_TAGS    = Regex(
            """</?(p|div|li|h[1-6]|br|section|article|tr)[^>]{0,100}>""",
            RegexOption.IGNORE_CASE
        )
        private val MULTI_SPACE   = Regex("""[ \t]{2,}""")
        private val MULTI_NEWLINE = Regex("""\n{3,}""")
        private val TITLE_TAG     = Regex(
            """<title[^>]{0,100}>(.*?)</title>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        private val HTML_TAG      = Regex("""<[^>]{1,200}>""")
    }

    /**
     * Busca usando la API JSON de DuckDuckGo.
     * FIX 3a: sin regex sobre HTML masivo — parsea JSON directamente.
     */
    suspend fun search(query: String, maxResults: Int = 5): ToolResult =
        withContext(Dispatchers.IO) {
            val limit = minOf(maxResults, MAX_RESULTS)

            // ── Intento 1: DDG Instant Answer API (JSON) ─────────────────────
            val jsonResult = searchDdgJson(query, limit)
            if (jsonResult != null) return@withContext jsonResult

            // ── Intento 2: Fallback DDG HTML con regex acotado ───────────────
            searchDdgHtmlFallback(query, limit)
        }

    // ── Búsqueda JSON (principal) ─────────────────────────────────────────────

    private suspend fun searchDdgJson(query: String, limit: Int): ToolResult? {
        return try {
            val response = httpClient.get(DDG_JSON_URL) {
                header("User-Agent", USER_AGENT)
                header("Accept", "application/json")
                parameter("q", query)
                parameter("format", "json")
                parameter("no_html", "1")
                parameter("skip_disambig", "1")
            }

            val bodyText = response.bodyAsText()
            if (response.status.value != 200) return null

            val root = Json.parseToJsonElement(bodyText).jsonObject
            val results = mutableListOf<JsonObject>()

            // RelatedTopics contiene los resultados de búsqueda web de DDG
            val topics = root["RelatedTopics"]?.jsonArray ?: return null
            for (topic in topics) {
                if (results.size >= limit) break
                val obj = topic.jsonObject

                // Ignorar sub-grupos (tienen "Topics" anidados en vez de "Result")
                val text = obj["Text"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: continue
                val url  = obj["FirstURL"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("http") } ?: continue

                results.add(buildJsonObject {
                    put("title",   text.take(120))
                    put("url",     url)
                    put("snippet", text.take(300))
                })
            }

            // Si DDG no dio resultados (query muy específica), deja pasar al fallback
            if (results.isEmpty()) return null

            ToolResult.Success(
                buildJsonObject {
                    put("query",   query)
                    put("count",   results.size)
                    put("source",  "ddg_json")
                    put("results", JsonArray(results))
                }.toString()
            )
        } catch (e: Exception) {
            null  // cualquier error → intenta fallback HTML
        }
    }

    // ── Fallback HTML acotado (solo si JSON no retorna resultados) ────────────

    private suspend fun searchDdgHtmlFallback(query: String, limit: Int): ToolResult {
        return try {
            val html = httpClient.get(DDG_HTML_URL) {
                header("User-Agent", USER_AGENT)
                header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                parameter("q", query)
            }.bodyAsText()

            // FIX 3a: en lugar de regex sobre el HTML completo, dividimos por líneas
            // y extraemos URLs de cada línea individualmente (operación O(n) segura)
            val results = mutableListOf<JsonObject>()
            val lines = html.lines()

            for (line in lines) {
                if (results.size >= limit) break
                // Busca líneas con href a URLs externas (no DDG interno)
                val urlMatch = LINK_HREF.find(line) ?: continue
                val url = urlMatch.groupValues[1]
                if ("duckduckgo.com" in url) continue

                // Texto de la línea como snippet: limpia tags acotados
                val snippet = STRIP_TAGS.replace(line, " ")
                    .decodeHtmlEntities()
                    .trim()
                    .take(200)
                    .takeIf { it.length > 20 } ?: continue

                results.add(buildJsonObject {
                    put("title",   snippet.take(80))
                    put("url",     url)
                    put("snippet", snippet)
                })
            }

            ToolResult.Success(
                buildJsonObject {
                    put("query",   query)
                    put("count",   results.size)
                    put("source",  "ddg_html_fallback")
                    put("results", JsonArray(results))
                }.toString()
            )
        } catch (e: Exception) {
            ToolResult.Error(
                message     = "Error buscando '$query': ${e.message}",
                isRetryable = true
            )
        }
    }

    /**
     * Extrae contenido de texto de una URL.
     * Descarga HTML → elimina ruido → texto plano → trunca a MAX_CONTENT.
     */
    suspend fun scrape(url: String, cssSelector: String? = null): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val html = httpClient.get(url) {
                    header("User-Agent", USER_AGENT)
                    header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                }.bodyAsText()

                val title = TITLE_TAG.find(html)?.groupValues?.get(1)
                    ?.let { stripHtml(it).trim() } ?: ""

                val content = extractText(html).take(MAX_CONTENT)

                ToolResult.Success(
                    buildJsonObject {
                        put("title",     title.take(200))
                        put("url",       url)
                        put("content",   content)
                        put("truncated", content.length >= MAX_CONTENT)
                    }.toString()
                )
            } catch (e: Exception) {
                ToolResult.Error(
                    message     = "Error scrapeando '$url': ${e.message}",
                    isRetryable = true
                )
            }
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stripHtml(html: String): String =
        HTML_TAG.replace(html, " ").decodeHtmlEntities().trim()

    private fun extractText(html: String): String {
        var text = SCRIPT_STYLE.replace(html, "")
        text = BLOCK_TAGS.replace(text, "\n")
        text = HTML_TAG.replace(text, "")
        text = text.decodeHtmlEntities()
        text = MULTI_SPACE.replace(text, " ")
        text = MULTI_NEWLINE.replace(text, "\n\n")
        return text.trim()
    }

    private fun String.decodeHtmlEntities(): String =
        this.replace("&amp;",  "&")
            .replace("&lt;",   "<")
            .replace("&gt;",   ">")
            .replace("&quot;", "\"")
            .replace("&#39;",  "'")
            .replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
}

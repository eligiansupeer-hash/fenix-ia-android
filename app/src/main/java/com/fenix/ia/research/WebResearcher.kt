package com.fenix.ia.research

import com.fenix.ia.tools.ToolResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Buscador y scraper web sin API keys de pago.
 *
 * Estrategia:
 * - Búsqueda: DuckDuckGo HTML estático (https://html.duckduckgo.com/html/)
 *   No requiere JS, no tiene CAPTCHA, coste cero.
 * - Scraping: extracción de texto via procesamiento de HTML con stdlib Kotlin.
 *   Sin dependencias externas de parsing — elimina conflictos de classpath.
 *
 * Restricciones:
 * - Sin WebView (R-03)
 * - Sin API keys de terceros
 * - Requests en Dispatchers.IO
 * - Contenido truncado a 8.000 chars (R-04)
 */
@Singleton
class WebResearcher @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val DDG_URL       = "https://html.duckduckgo.com/html/"
        private const val MAX_CONTENT   = 8_000
        private const val MAX_RESULTS   = 10

        // Regex para extraer bloques de resultado DDG del HTML estático
        private val RESULT_BLOCK  = Regex("""<div class="result[^"]*"[^>]*>(.*?)</div>\s*</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val TITLE_HREF    = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val SNIPPET_TEXT  = Regex("""class="result__snippet"[^>]*>(.*?)</(?:span|div)>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        // Regex para limpiar HTML genérico → texto plano
        private val SCRIPT_STYLE  = Regex("""<(script|style|nav|footer|header)[^>]*>.*?</\1>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val HTML_TAGS     = Regex("""<[^>]+>""")
        private val MULTI_SPACE   = Regex("""[ \t]{2,}""")
        private val MULTI_NEWLINE = Regex("""\n{3,}""")

        // Extrae texto de entre tags HTML
        private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    }

    /**
     * Busca en DuckDuckGo HTML y retorna resultados con título, URL y snippet.
     */
    suspend fun search(query: String, maxResults: Int = 5): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val html = httpClient.get(DDG_URL) {
                    header("User-Agent", USER_AGENT)
                    header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                    header("Accept", "text/html,application/xhtml+xml")
                    parameter("q", query)
                }.bodyAsText()

                val results = mutableListOf<JsonObject>()
                val limit   = minOf(maxResults, MAX_RESULTS)

                // Extrae cada bloque de resultado del HTML de DDG
                for (block in RESULT_BLOCK.findAll(html)) {
                    if (results.size >= limit) break
                    val blockHtml = block.groupValues[1]

                    val titleMatch   = TITLE_HREF.find(blockHtml) ?: continue
                    val snippetMatch = SNIPPET_TEXT.find(blockHtml)

                    val url     = titleMatch.groupValues[1].trim()
                    val title   = stripHtml(titleMatch.groupValues[2]).trim()
                    val snippet = snippetMatch?.let { stripHtml(it.groupValues[1]).trim() } ?: ""

                    if (title.isBlank() || !url.startsWith("http")) continue

                    results.add(buildJsonObject {
                        put("title",   title)
                        put("url",     url)
                        put("snippet", snippet)
                    })
                }

                ToolResult.Success(
                    buildJsonObject {
                        put("query",   query)
                        put("count",   results.size)
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
     *
     * 1. Descarga HTML con Ktor
     * 2. Elimina scripts, estilos, nav y footer
     * 3. Convierte tags restantes a saltos de línea
     * 4. Colapsa espacios y líneas vacías
     * 5. Trunca a MAX_CONTENT (R-04)
     */
    suspend fun scrape(url: String, cssSelector: String? = null): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val html = httpClient.get(url) {
                    header("User-Agent", USER_AGENT)
                    header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                }.bodyAsText()

                // Extrae título de la página
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

    // ── Helpers de extracción de texto ────────────────────────────────────────

    /**
     * Elimina tags HTML y devuelve texto limpio.
     */
    private fun stripHtml(html: String): String =
        html.replace(HTML_TAGS, " ")
            .replace("&amp;",  "&")
            .replace("&lt;",   "<")
            .replace("&gt;",   ">")
            .replace("&quot;", "\"")
            .replace("&#39;",  "'")
            .replace("&nbsp;", " ")
            .trim()

    /**
     * Extrae texto legible de HTML:
     * 1. Elimina scripts, estilos, nav, footer (bloques de ruido)
     * 2. Convierte <br>, <p>, <div>, <li>, <h1-h6> en saltos de línea
     * 3. Elimina todos los tags restantes
     * 4. Colapsa espacios múltiples y líneas vacías
     */
    private fun extractText(html: String): String {
        var text = html

        // Elimina bloques de ruido completos
        text = SCRIPT_STYLE.replace(text, "")

        // Convierte tags de bloque en saltos de línea para preservar estructura
        text = text.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("""</?(p|div|li|h[1-6]|section|article|main|header|tr)[^>]*>""",
            RegexOption.IGNORE_CASE), "\n")

        // Elimina todos los tags restantes
        text = HTML_TAGS.replace(text, "")

        // Decodifica entidades básicas
        text = text
            .replace("&amp;",  "&")
            .replace("&lt;",   "<")
            .replace("&gt;",   ">")
            .replace("&quot;", "\"")
            .replace("&#39;",  "'")
            .replace("&nbsp;", " ")

        // Colapsa espacios múltiples y líneas vacías excesivas
        text = MULTI_SPACE.replace(text, " ")
        text = MULTI_NEWLINE.replace(text, "\n\n")

        return text.trim()
    }
}

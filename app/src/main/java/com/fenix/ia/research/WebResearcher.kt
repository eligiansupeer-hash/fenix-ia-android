package com.fenix.ia.research

import com.fenix.ia.tools.ToolResult
import com.fleeksoft.ksoup.Ksoup
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
 *   No requiere JS, no tiene CAPTCHA, respeta robots.txt, coste cero.
 * - Scraping: Ksoup (parser HTML puro Kotlin) extrae contenido semántico
 *   removiendo ruido (nav, scripts, ads, footers).
 *
 * Restricciones:
 * - Sin WebView (R-03: no DCL externo)
 * - Sin API keys de terceros
 * - Requests en Dispatchers.IO
 * - Contenido scrapeado truncado a 8.000 chars (R-04: sin carga masiva en heap)
 */
@Singleton
class WebResearcher @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val DDG_URL = "https://html.duckduckgo.com/html/"
        private const val MAX_CONTENT_CHARS = 8_000
        private const val MAX_ELEMENTS = 50
    }

    /**
     * Busca en DuckDuckGo HTML y retorna lista de resultados con título, URL y snippet.
     *
     * @param query      Consulta de búsqueda
     * @param maxResults Máximo de resultados a retornar (por defecto 5)
     * @return [ToolResult.Success] con JSON `{results:[{title,url,snippet}]}`
     *         [ToolResult.Error]   con mensaje y isRetryable=true en falla de red
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

                val doc     = Ksoup.parse(html)
                val results = doc.select("#links .result").take(maxResults).mapNotNull { el ->
                    val title   = el.select(".result__title a").text().trim()
                    val url     = el.select(".result__title a").attr("href").trim()
                    val snippet = el.select(".result__snippet").text().trim()

                    // Filtra resultados sin URL válida o sin título
                    if (title.isBlank() || !url.startsWith("http")) return@mapNotNull null

                    buildJsonObject {
                        put("title",   title)
                        put("url",     url)
                        put("snippet", snippet)
                    }
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
     * Extrae contenido semántico de una URL usando Ksoup.
     *
     * Proceso:
     * 1. Descarga HTML con Ktor
     * 2. Parsea con Ksoup
     * 3. Remueve ruido: nav, script, style, ads, footer, cookie banners
     * 4. Extrae texto de selectores semánticos: article, main, p, h1-h3, li
     * 5. Trunca a MAX_CONTENT_CHARS (R-04)
     *
     * @param url         URL a scrapear
     * @param cssSelector Selector CSS opcional para extraer sección específica
     * @return [ToolResult.Success] con JSON `{title, url, content, truncated}`
     *         [ToolResult.Error]   en falla de red o parsing
     */
    suspend fun scrape(url: String, cssSelector: String? = null): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val html = httpClient.get(url) {
                    header("User-Agent", USER_AGENT)
                    header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                }.bodyAsText()

                val doc = Ksoup.parse(html)

                // Remueve ruido semántico antes de extraer texto
                doc.select(
                    "nav, script, style, .ad, .advertisement, .ads, " +
                    "footer, #cookie-banner, .cookie, .popup, .modal, " +
                    ".sidebar, .widget, header .nav, .menu"
                ).remove()

                val content = if (cssSelector != null) {
                    // Selector específico solicitado por el caller
                    doc.select(cssSelector).text()
                } else {
                    // Selectores semánticos por prioridad: article/main primero, luego párrafos
                    val semantic = doc.select("article, main, [role=main], .content, .post-content")
                    if (semantic.isNotEmpty()) {
                        semantic.take(MAX_ELEMENTS).joinToString("\n") { it.text() }
                    } else {
                        // Fallback: extraer párrafos y encabezados sueltos
                        doc.select("p, h1, h2, h3, li")
                            .take(MAX_ELEMENTS)
                            .joinToString("\n") { it.text() }
                    }
                }.take(MAX_CONTENT_CHARS)

                ToolResult.Success(
                    buildJsonObject {
                        put("title",     doc.title().take(200))
                        put("url",       url)
                        put("content",   content)
                        put("truncated", content.length >= MAX_CONTENT_CHARS)
                    }.toString()
                )
            } catch (e: Exception) {
                ToolResult.Error(
                    message     = "Error scrapeando '$url': ${e.message}",
                    isRetryable = true
                )
            }
        }
}

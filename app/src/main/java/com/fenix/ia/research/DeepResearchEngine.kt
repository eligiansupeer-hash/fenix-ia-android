package com.fenix.ia.research

import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.data.remote.LlmMessage
import com.fenix.ia.data.remote.StreamEvent
import com.fenix.ia.domain.model.AgentRole
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor de investigación profunda sin API keys de pago.
 *
 * Implementa el patrón IterDRAG (Iterative Dense Retrieval-Augmented Generation):
 *   1. Descompone el tema en sub-queries
 *   2. Para cada iteración:
 *      a. Busca cada sub-query en DuckDuckGo (paralelo con Semaphore(2))
 *      b. Scrapea las URLs más relevantes
 *      c. Indexa el contenido en ObjectBox (RAG local)
 *   3. Calcula cobertura: si >= 85% para antes de agotar iteraciones
 *   4. Sintetiza todo con el agente SINTETIZADOR
 *
 * Restricciones:
 * - Semaphore(2): máximo 2 requests HTTP concurrentes (no saturar la red)
 * - Contenido scrapeado acotado a 8.000 chars por URL (WebResearcher)
 * - Contexto de síntesis truncado a 20.000 chars (R-04: sin desborde de heap)
 * - Máximo 3 iteraciones hardcodeado (minOf(depth, 3))
 */
@Singleton
class DeepResearchEngine @Inject constructor(
    private val web: WebResearcher,
    private val rag: RagEngine,
    private val router: LlmInferenceRouter
) {
    companion object {
        private const val MAX_ITERATIONS    = 3
        private const val COVERAGE_TARGET   = 0.85f
        private const val CHARS_PER_UNIT    = 5_000
        private const val MAX_SYNTHESIS_LEN = 20_000
        private const val MAX_RESULTS_PER_QUERY = 4
        private const val MAX_URLS_PER_QUERY    = 3
    }

    /**
     * Lanza una investigación profunda sobre [topic] y emite eventos de progreso.
     *
     * @param topic     Tema a investigar en lenguaje natural
     * @param projectId ID del proyecto (para indexar en RAG)
     * @param depth     Número de iteraciones deseado (1–3, hardcap=3)
     * @param provider  Proveedor LLM para síntesis (null = GROQ)
     */
    fun research(
        topic: String,
        projectId: String,
        depth: Int = 2,
        provider: ApiProvider? = null
    ): Flow<ResearchEvent> = flow {

        emit(ResearchEvent.Started(topic))

        // Sub-queries: el tema general + variaciones que amplían cobertura
        val subQueries = listOf(
            topic,
            "$topic definición y concepto",
            "$topic ejemplos prácticos",
            "$topic aplicaciones actuales"
        )
        emit(ResearchEvent.QueriesReady(subQueries))

        val allContent = mutableListOf<String>()
        val allSources = mutableListOf<String>()
        val semaphore  = Semaphore(2) // máximo 2 requests HTTP simultáneos

        val iterations = minOf(depth, MAX_ITERATIONS)

        repeat(iterations) { iter ->
            emit(ResearchEvent.IterationStarted(iter + 1))

            // Búsqueda y scraping en paralelo con límite de concurrencia
            coroutineScope {
                subQueries.map { query ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val searchResult = web.search(query, MAX_RESULTS_PER_QUERY)
                            if (searchResult !is ToolResult.Success) return@withPermit

                            // Extrae URLs del JSON de resultados
                            val urls = try {
                                Json.parseToJsonElement(searchResult.outputJson)
                                    .jsonObject["results"]!!
                                    .jsonArray
                                    .mapNotNull { el ->
                                        el.jsonObject["url"]?.jsonPrimitive?.content
                                            ?.takeIf { it.startsWith("http") }
                                    }
                                    .take(MAX_URLS_PER_QUERY)
                            } catch (e: Exception) { emptyList() }

                            // Scrapea cada URL y la indexa en ObjectBox
                            for (url in urls) {
                                val scraped = web.scrape(url)
                                if (scraped !is ToolResult.Success) continue

                                val text = try {
                                    Json.parseToJsonElement(scraped.outputJson)
                                        .jsonObject["content"]?.jsonPrimitive?.content
                                        ?: continue
                                } catch (e: Exception) { continue }

                                if (text.isBlank()) continue

                                allContent.add(text)
                                allSources.add(url)

                                // Indexar en RAG local (Blackboard persistente)
                                try {
                                    rag.indexDocument(
                                        projectId      = projectId.hashCode().toLong(),
                                        documentNodeId = "research_${url.hashCode()}_${iter}",
                                        text           = text
                                    )
                                } catch (e: Exception) {
                                    // No bloquear si falla el indexado
                                }

                                emit(
                                    ResearchEvent.ContentIndexed(
                                        url     = url,
                                        preview = text.take(150).replace('\n', ' ')
                                    )
                                )
                            }
                        }
                    }
                }.awaitAll()
            }

            // Cobertura: fracción del target de chars acumulados (0.0 – 1.0)
            val totalChars = allContent.sumOf { it.length }
            val coverage   = minOf(totalChars.toFloat() / (CHARS_PER_UNIT * iterations), 1f)
            emit(ResearchEvent.IterationDone(iter + 1, coverage))

            // Convergencia anticipada si ya se alcanzó el target
            if (coverage >= COVERAGE_TARGET) return@repeat
        }

        // ── Síntesis final con SINTETIZADOR ──────────────────────────────────
        emit(ResearchEvent.Synthesizing)

        val context = allContent
            .joinToString("\n---\n")
            .take(MAX_SYNTHESIS_LEN)

        val synthesisProvider = provider ?: ApiProvider.GROQ
        var synthesis = ""

        router.streamCompletion(
            messages = listOf(
                LlmMessage(
                    "user",
                    "Sintetiza toda la información disponible sobre: $topic\n\n$context"
                )
            ),
            systemPrompt = AgentRole.SINTETIZADOR.systemPrompt,
            provider     = synthesisProvider
        ).collect { event ->
            if (event is StreamEvent.Token) synthesis += event.text
        }

        emit(ResearchEvent.Done(synthesis, allSources.distinct()))

    }.flowOn(Dispatchers.IO)
}

/**
 * Eventos emitidos por [DeepResearchEngine.research] durante la investigación.
 */
sealed class ResearchEvent {
    /** Investigación iniciada con el tema solicitado. */
    data class Started(val topic: String) : ResearchEvent()

    /** Sub-queries generadas a partir del tema. */
    data class QueriesReady(val queries: List<String>) : ResearchEvent()

    /** Comienza la iteración N (1-indexed). */
    data class IterationStarted(val n: Int) : ResearchEvent()

    /** Contenido scrapeado e indexado en ObjectBox. */
    data class ContentIndexed(val url: String, val preview: String) : ResearchEvent()

    /** Iteración N completada con cobertura actual (0.0 – 1.0). */
    data class IterationDone(val n: Int, val coverage: Float) : ResearchEvent()

    /** Iniciando síntesis del contenido acumulado. */
    object Synthesizing : ResearchEvent()

    /** Investigación completada con síntesis y lista de fuentes. */
    data class Done(val synthesis: String, val sources: List<String>) : ResearchEvent()
}

/** Convierte el evento a string legible para mostrar en la UI. */
fun ResearchEvent.toDisplayString(): String = when (this) {
    is ResearchEvent.Started         -> "🔍 Investigando: $topic"
    is ResearchEvent.QueriesReady    -> "📋 ${queries.size} sub-queries generadas"
    is ResearchEvent.IterationStarted -> "🔄 Iteración $n"
    is ResearchEvent.ContentIndexed  -> "📥 Indexado: ${url.take(60)}…\n   ${preview.take(80)}…"
    is ResearchEvent.IterationDone   -> "✅ Iter $n completa — cobertura: ${(coverage * 100).toInt()}%"
    is ResearchEvent.Synthesizing    -> "✍️ Sintetizando resultados…"
    is ResearchEvent.Done            -> "🏁 Investigación completa — ${sources.size} fuentes"
}

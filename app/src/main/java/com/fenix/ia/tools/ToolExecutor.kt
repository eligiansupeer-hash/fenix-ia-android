package com.fenix.ia.tools

import android.content.Context
import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType
import com.fenix.ia.research.WebResearcher
import com.fenix.ia.sandbox.DynamicExecutionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatcher de ejecucion de tools nativas.
 * Cada tool implementa su contrato JSON segun inputSchema/outputSchema.
 *
 * RESTRICCION R-04: read_file usa bufferedReader con maxChars, nunca carga el archivo entero.
 * RESTRICCION R-05: el codigo JS se pasa al sandbox como script separado de los datos.
 *
 * Tools implementadas:
 *   - read_file, create_file           (filesystem)
 *   - store_knowledge, retrieve_context (RAG / ObjectBox)
 *   - web_search, scrape_content        (NODO-C1: WebResearcher + Ksoup)
 *   - run_code                          (JavaScriptSandbox)
 */
@Singleton
class ToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ragEngine: RagEngine,
    private val sandbox: DynamicExecutionEngine,
    private val webResearcher: WebResearcher          // NODO-C1
) {
    suspend fun execute(tool: Tool, argsJson: String): ToolResult {
        val args = try {
            Json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return ToolResult.Error("JSON de argumentos invalido: ${e.message}")
        }

        return try {
            when (tool.executionType) {
                ToolExecutionType.JAVASCRIPT    -> executeSandbox(tool, args)
                ToolExecutionType.NATIVE_KOTLIN -> executeNative(tool, args)
                ToolExecutionType.HTTP_EXTERNAL -> ToolResult.Error(
                    "HTTP_EXTERNAL no implementado en este nodo"
                )
            }
        } catch (e: Exception) {
            ToolResult.Error(e.message ?: "Error desconocido en ${tool.name}")
        }
    }

    // ---- Dispatcher nativo ----

    private suspend fun executeNative(tool: Tool, args: JsonObject): ToolResult =
        when (tool.name) {
            "read_file"        -> executeReadFile(args)
            "create_file"      -> executeCreateFile(args)
            "store_knowledge"  -> executeStoreKnowledge(args)
            "retrieve_context" -> executeRetrieveContext(args)
            "web_search"       -> executeWebSearch(args)      // NODO-C1
            "scrape_content"   -> executeScrapeContent(args)  // NODO-C1
            else -> ToolResult.Error(
                "Tool [${tool.name}] no tiene implementacion nativa en este nodo. " +
                "Sera despachada por el OrchestratorEngine en fases posteriores."
            )
        }

    // ---- Implementaciones individuales ----

    /** R-04: stream con maxChars — nunca cargar archivo completo en heap. */
    private fun executeReadFile(args: JsonObject): ToolResult {
        val path     = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'path' requerido")
        val maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull ?: 10_000

        val file = File(path)
        if (!file.exists()) return ToolResult.Error("Archivo no encontrado: $path")

        val content = file.bufferedReader().use { reader ->
            val buf  = CharArray(maxChars)
            val read = reader.read(buf)
            if (read > 0) String(buf, 0, read) else ""
        }

        return ToolResult.Success(buildJsonObject {
            put("content",   content)
            put("truncated", file.length() > maxChars)
        }.toString())
    }

    /**
     * Crea un archivo de texto en el directorio Scoped Storage del proyecto.
     * Path resultante: filesDir/projects/{projectId}/{fileName}
     */
    private fun executeCreateFile(args: JsonObject): ToolResult {
        val fileName  = args["fileName"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'fileName' requerido")
        val content   = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'content' requerido")
        val projectId = args["projectId"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'projectId' requerido")

        val dir  = File(context.filesDir, "projects/$projectId").also { it.mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)

        return ToolResult.Success(buildJsonObject {
            put("path",    file.absolutePath)
            put("success", true)
        }.toString())
    }

    /** Indexa texto en ObjectBox via RagEngine. */
    private suspend fun executeStoreKnowledge(args: JsonObject): ToolResult {
        val text      = args["text"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'text' requerido")
        val projectId = args["projectId"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'projectId' requerido")
        val tag       = args["tag"]?.jsonPrimitive?.content ?: "manual"

        val estimatedChunks = (text.split("\\s+".toRegex()).size / 700).coerceAtLeast(1)
        ragEngine.indexDocument(
            projectId      = projectId.hashCode().toLong(),
            documentNodeId = "knowledge_${tag}_${System.currentTimeMillis()}",
            text           = text
        )

        return ToolResult.Success(buildJsonObject {
            put("chunksStored", estimatedChunks)
        }.toString())
    }

    /** Recupera fragmentos RAG relevantes para una query. */
    private suspend fun executeRetrieveContext(args: JsonObject): ToolResult {
        val query     = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'query' requerido")
        val projectId = args["projectId"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'projectId' requerido")
        val limit     = args["limit"]?.jsonPrimitive?.intOrNull ?: 5

        val chunks = ragEngine.search(query, projectId.hashCode().toLong(), limit)

        return ToolResult.Success(buildJsonObject {
            put("chunks", buildJsonArray {
                chunks.forEach { chunk ->
                    add(buildJsonObject {
                        put("text",  chunk.textPayload)
                        put("score", 0.0)
                    })
                }
            })
        }.toString())
    }

    // ---- Web tools (NODO-C1) ----

    /**
     * Búsqueda DuckDuckGo HTML sin API key.
     * Retorna: {results:[{title, url, snippet}]}
     */
    private suspend fun executeWebSearch(args: JsonObject): ToolResult {
        val query      = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'query' requerido")
        val maxResults = args["maxResults"]?.jsonPrimitive?.intOrNull ?: 5
        return webResearcher.search(query, maxResults)
    }

    /**
     * Scraping semántico de URL con Ksoup.
     * Retorna: {title, url, content, truncated}
     */
    private suspend fun executeScrapeContent(args: JsonObject): ToolResult {
        val url         = args["url"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'url' requerido")
        val cssSelector = args["cssSelector"]?.jsonPrimitive?.contentOrNull
        return webResearcher.scrape(url, cssSelector)
    }

    // ---- Sandbox JS ----

    /**
     * R-05: el codigo y los datos viajan separados al sandbox.
     */
    private suspend fun executeSandbox(tool: Tool, args: JsonObject): ToolResult {
        val code = args["code"]?.jsonPrimitive?.content
            ?: tool.jsBody
            ?: return ToolResult.Error("Sin codigo JS: proporciona 'code' en args o jsBody en la tool")

        val inputData = args["inputData"]?.jsonPrimitive?.content ?: "{}"

        return try {
            val result = sandbox.execute(code, inputData)
            ToolResult.Success(buildJsonObject {
                put("result", result)
                put("error",  "")
            }.toString())
        } catch (e: SecurityException) {
            ToolResult.Error("PolicyEngine rechazo el script: ${e.message}")
        } catch (e: Exception) {
            val msg       = e.message ?: "Error desconocido en sandbox"
            val isTimeout = msg.contains("timeout", ignoreCase = true)
            ToolResult.Error(
                message     = if (isTimeout) "Timeout en sandbox (limite 10s)" else msg,
                isRetryable = false
            )
        }
    }
}

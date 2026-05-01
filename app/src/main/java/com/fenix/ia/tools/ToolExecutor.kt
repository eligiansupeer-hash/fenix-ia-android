package com.fenix.ia.tools

import android.content.Context
import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.data.remote.ApiProvider
import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.data.remote.LlmMessage
import com.fenix.ia.data.remote.StreamEvent
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType
import com.fenix.ia.domain.repository.ToolRepository
import com.fenix.ia.research.WebResearcher
import com.fenix.ia.sandbox.DynamicExecutionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatcher de ejecución de tools nativas.
 * Cada tool implementa su contrato JSON según inputSchema/outputSchema.
 *
 * RESTRICCIÓN R-04: read_file usa bufferedReader con maxChars — nunca carga el archivo entero.
 * RESTRICCIÓN R-05: el código JS viaja al sandbox separado de los datos de usuario.
 *
 * Tools implementadas (14/14):
 *   Filesystem  : read_file, create_file
 *   RAG         : store_knowledge, retrieve_context, search_in_project
 *   Web         : web_search, scrape_content, deep_research
 *   LLM nativo  : summarize, translate
 *   Documentos  : create_docx, create_pdf
 *   Sistema     : create_new_tool
 *   Sandbox     : run_code (JAVASCRIPT)
 */
@Singleton
class ToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ragEngine: RagEngine,
    private val sandbox: DynamicExecutionEngine,
    private val webResearcher: WebResearcher,
    private val llmRouter: LlmInferenceRouter,      // summarize, translate, deep_research
    private val toolRepository: ToolRepository       // create_new_tool
) {
    suspend fun execute(tool: Tool, argsJson: String): ToolResult {
        val args = try {
            Json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return ToolResult.Error("JSON de argumentos inválido: ${e.message}")
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

    // ── Dispatcher nativo ────────────────────────────────────────────────────

    private suspend fun executeNative(tool: Tool, args: JsonObject): ToolResult =
        when (tool.name) {
            // Filesystem
            "read_file"        -> executeReadFile(args)
            "create_file"      -> executeCreateFile(args)
            // RAG
            "store_knowledge"  -> executeStoreKnowledge(args)
            "retrieve_context" -> executeRetrieveContext(args)
            "search_in_project"-> executeSearchInProject(args)
            // Web
            "web_search"       -> executeWebSearch(args)
            "scrape_content"   -> executeScrapeContent(args)
            "deep_research"    -> executeDeepResearch(args)
            // LLM nativo
            "summarize"        -> executeSummarize(args)
            "translate"        -> executeTranslate(args)
            // Documentos
            "create_docx"      -> executeCreateDocx(args)
            "create_pdf"       -> executeCreatePdf(args)
            // Sistema
            "create_new_tool"  -> executeCreateNewTool(args)
            else -> ToolResult.Error(
                "Tool [${tool.name}] sin implementación nativa. " +
                "Verificá que el nombre coincida exactamente con el catálogo."
            )
        }

    // ── Filesystem ───────────────────────────────────────────────────────────

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

    // ── RAG ──────────────────────────────────────────────────────────────────

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

    private suspend fun executeSearchInProject(args: JsonObject): ToolResult {
        val query     = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'query' requerido")
        val projectId = args["projectId"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'projectId' requerido")
        val limit     = args["limit"]?.jsonPrimitive?.intOrNull ?: 10

        val chunks = ragEngine.search(query, projectId.hashCode().toLong(), limit)

        return ToolResult.Success(buildJsonObject {
            put("results", buildJsonArray {
                chunks.forEach { chunk ->
                    add(buildJsonObject {
                        put("docName", chunk.documentNodeId)
                        put("snippet", chunk.textPayload.take(400))
                    })
                }
            })
        }.toString())
    }

    // ── Web ───────────────────────────────────────────────────────────────────

    private suspend fun executeWebSearch(args: JsonObject): ToolResult {
        val query      = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'query' requerido")
        val maxResults = args["maxResults"]?.jsonPrimitive?.intOrNull ?: 5
        return webResearcher.search(query, maxResults)
    }

    private suspend fun executeScrapeContent(args: JsonObject): ToolResult {
        val url         = args["url"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'url' requerido")
        val cssSelector = args["cssSelector"]?.jsonPrimitive?.contentOrNull
        return webResearcher.scrape(url, cssSelector)
    }

    private suspend fun executeDeepResearch(args: JsonObject): ToolResult {
        val topic     = args["topic"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'topic' requerido")
        val depth     = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        val projectId = args["projectId"]?.jsonPrimitive?.content ?: ""

        val allResults = StringBuilder()

        repeat(minOf(depth, 3)) { i ->
            val subQuery = if (i == 0) topic else "$topic análisis profundo parte ${i + 1}"
            val searchResult = webResearcher.search(subQuery, 3)
            if (searchResult is ToolResult.Success) {
                allResults.appendLine(searchResult.outputJson)
                // Indexar resultados en RAG si tenemos projectId
                if (projectId.isNotBlank()) {
                    try {
                        ragEngine.indexDocument(
                            projectId      = projectId.hashCode().toLong(),
                            documentNodeId = "research_${topic.hashCode()}_iter$i",
                            text           = searchResult.outputJson
                        )
                    } catch (_: Exception) {}
                }
            }
        }

        var report = ""
        llmRouter.streamCompletion(
            messages     = listOf(LlmMessage("user",
                "Sintetizá esta investigación sobre '$topic' en un reporte estructurado en Markdown:\n\n$allResults")),
            systemPrompt = "Investigador experto. Citá fuentes. Formato Markdown. Sé conciso y preciso.",
            provider     = ApiProvider.GROQ,
            temperature  = 0.4f
        ).collect { event ->
            if (event is StreamEvent.Token) report += event.text
        }

        return ToolResult.Success(buildJsonObject {
            put("report",  report.trim())
            put("sources", buildJsonArray {}) // las fuentes están referenciadas dentro del report
        }.toString())
    }

    // ── LLM nativo ───────────────────────────────────────────────────────────

    private suspend fun executeSummarize(args: JsonObject): ToolResult {
        val text     = args["text"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'text' requerido")
        val maxWords = args["maxWords"]?.jsonPrimitive?.intOrNull ?: 200

        var summary = ""
        llmRouter.streamCompletion(
            messages     = listOf(LlmMessage("user",
                "Resumí el siguiente texto en máximo $maxWords palabras, conservando las ideas clave:\n\n$text")),
            systemPrompt = "Asistente de síntesis. Respondé SOLO con el resumen, sin comentarios ni encabezados.",
            provider     = ApiProvider.GROQ,
            temperature  = 0.3f
        ).collect { event ->
            if (event is StreamEvent.Token) summary += event.text
        }

        return ToolResult.Success(buildJsonObject {
            put("summary", summary.trim())
        }.toString())
    }

    private suspend fun executeTranslate(args: JsonObject): ToolResult {
        val text       = args["text"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'text' requerido")
        val targetLang = args["targetLang"]?.jsonPrimitive?.content ?: "español"

        var translated = ""
        llmRouter.streamCompletion(
            messages     = listOf(LlmMessage("user", "Traducí al $targetLang:\n\n$text")),
            systemPrompt = "Traductor preciso. Respondé SOLO con la traducción, sin explicaciones ni comentarios.",
            provider     = ApiProvider.GROQ,
            temperature  = 0.1f
        ).collect { event ->
            if (event is StreamEvent.Token) translated += event.text
        }

        return ToolResult.Success(buildJsonObject {
            put("translated", translated.trim())
        }.toString())
    }

    // ── Documentos ───────────────────────────────────────────────────────────

    private fun executeCreateDocx(args: JsonObject): ToolResult {
        val title      = args["title"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'title' requerido")
        val content    = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'content' requerido")
        val outputPath = args["outputPath"]?.jsonPrimitive?.contentOrNull
            ?: "${context.filesDir}/documents/${title.replace(' ', '_')}.docx"

        return try {
            val doc  = org.apache.poi.xwpf.usermodel.XWPFDocument()
            // Título
            val titlePara = doc.createParagraph()
            val titleRun  = titlePara.createRun()
            titleRun.isBold   = true
            titleRun.fontSize = 16
            titleRun.setText(title)
            // Contenido
            content.split("\n").forEach { line ->
                val para = doc.createParagraph()
                para.createRun().setText(line)
            }
            val file = File(outputPath).also { it.parentFile?.mkdirs() }
            file.outputStream().use { doc.write(it) }
            doc.close()
            ToolResult.Success(buildJsonObject {
                put("path",    file.absolutePath)
                put("success", true)
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Error creando DOCX: ${e.message}")
        }
    }

    private fun executeCreatePdf(args: JsonObject): ToolResult {
        val title      = args["title"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'title' requerido")
        val content    = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'content' requerido")
        val outputPath = args["outputPath"]?.jsonPrimitive?.contentOrNull
            ?: "${context.filesDir}/documents/${title.replace(' ', '_')}.pdf"

        return try {
            val paint    = android.graphics.Paint()
            val pdfDoc   = android.graphics.pdf.PdfDocument()
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page     = pdfDoc.startPage(pageInfo)
            val canvas   = page.canvas

            // Título
            paint.textSize       = 18f
            paint.isFakeBoldText = true
            canvas.drawText(title.take(80), 40f, 60f, paint)

            // Contenido
            paint.textSize       = 12f
            paint.isFakeBoldText = false
            var y = 100f
            content.split("\n").forEach { line ->
                if (y < 800f) {
                    canvas.drawText(line.take(80), 40f, y, paint)
                    y += 20f
                }
            }

            pdfDoc.finishPage(page)
            val file = File(outputPath).also { it.parentFile?.mkdirs() }
            file.outputStream().use { pdfDoc.writeTo(it) }
            pdfDoc.close()

            ToolResult.Success(buildJsonObject {
                put("path",  file.absolutePath)
                put("pages", 1)
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Error creando PDF: ${e.message}")
        }
    }

    // ── Sistema ───────────────────────────────────────────────────────────────

    private suspend fun executeCreateNewTool(args: JsonObject): ToolResult {
        val name        = args["name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'name' requerido")
        val description = args["description"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'description' requerido")
        val jsBody      = args["jsBody"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'jsBody' requerido")
        val inputSchema = args["inputSchema"]?.jsonPrimitive?.contentOrNull ?: "{\"input\":\"string\"}"

        val cleanName = name
            .lowercase()
            .replace(' ', '_')
            .replace(Regex("[^a-z0-9_]"), "")
            .take(30)

        return try {
            toolRepository.insertTool(
                com.fenix.ia.domain.model.Tool(
                    id              = UUID.randomUUID().toString(),
                    name            = cleanName,
                    description     = description,
                    inputSchema     = inputSchema,
                    outputSchema    = "{\"result\":\"string\"}",
                    permissions     = emptyList(),
                    executionType   = ToolExecutionType.JAVASCRIPT,
                    jsBody          = jsBody,
                    isEnabled       = true,
                    isUserGenerated = true,
                    createdAt       = System.currentTimeMillis()
                )
            )
            ToolResult.Success(buildJsonObject {
                put("toolId",  cleanName)
                put("success", true)
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Error persistiendo tool '$cleanName': ${e.message}")
        }
    }

    // ── Sandbox JS ───────────────────────────────────────────────────────────

    /** R-05: código y datos viajan separados al sandbox. */
    private suspend fun executeSandbox(tool: Tool, args: JsonObject): ToolResult {
        val code = args["code"]?.jsonPrimitive?.content
            ?: tool.jsBody
            ?: return ToolResult.Error("Sin código JS: proporcioná 'code' en args o jsBody en la tool")

        val inputData = args["inputData"]?.jsonPrimitive?.content ?: "{}"

        return try {
            val result = sandbox.execute(code, inputData)
            ToolResult.Success(buildJsonObject {
                put("result", result)
                put("error",  "")
            }.toString())
        } catch (e: SecurityException) {
            ToolResult.Error("PolicyEngine rechazó el script: ${e.message}")
        } catch (e: Exception) {
            val msg       = e.message ?: "Error desconocido en sandbox"
            val isTimeout = msg.contains("timeout", ignoreCase = true)
            ToolResult.Error(
                message     = if (isTimeout) "Timeout en sandbox (límite 10s)" else msg,
                isRetryable = false
            )
        }
    }
}

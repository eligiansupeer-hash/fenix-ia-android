package com.fenix.ia.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.fenix.ia.audit.AuditLogger
import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.data.local.objectbox.RagProjectId
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.data.remote.LlmMessage
import com.fenix.ia.data.remote.StreamEvent
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType
import com.fenix.ia.domain.repository.ToolRepository
import com.fenix.ia.ingestion.DocxSimple
import com.fenix.ia.ingestion.DocxTextExtractor
import com.fenix.ia.ingestion.PdfTextExtractor
import com.fenix.ia.research.WebResearcher
import com.fenix.ia.sandbox.DynamicExecutionEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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
    private val toolRepository: ToolRepository,      // create_new_tool
    private val pdfExtractor: PdfTextExtractor,
    private val docxExtractor: DocxTextExtractor
) {
    suspend fun execute(tool: Tool, argsJson: String): ToolResult {
        AuditLogger.action("tool_execute_start", mapOf("tool" to tool.name, "type" to tool.executionType.name))
        val startedAt = System.currentTimeMillis()
        val args = try {
            Json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return ToolResult.Error("JSON de argumentos inválido: ${e.message}")
        }

        val result = try {
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
        val latencyMs = System.currentTimeMillis() - startedAt
        when (result) {
            is ToolResult.Success -> AuditLogger.action(
                "tool_execute_success",
                mapOf("tool" to tool.name, "latencyMs" to latencyMs.toString(), "chars" to result.outputJson.length.toString())
            )
            is ToolResult.Error -> AuditLogger.action(
                "tool_execute_error",
                mapOf("tool" to tool.name, "latencyMs" to latencyMs.toString(), "message" to result.message)
            )
        }
        return result
    }

    // ── Dispatcher nativo ────────────────────────────────────────────────────

    private suspend fun executeNative(tool: Tool, args: JsonObject): ToolResult =
        when (tool.name) {
            // Filesystem
            "read_file"        -> executeReadFile(args)
            "create_file"      -> executeCreateFile(args)
            "edit_file"        -> executeEditFile(args)
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
            "read_docx"        -> executeReadDocx(args)
            "edit_docx"        -> executeEditDocx(args)
            "create_pdf"       -> executeCreatePdf(args)
            "read_pdf"         -> executeReadPdf(args)
            "ocr_pdf"          -> executeReadPdf(args)
            "ocr_image"        -> executeOcrImage(args)
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

        return FileToolRunner.readFile(context, args)
    }

    private fun executeCreateFile(args: JsonObject): ToolResult {
        val fileName  = args["fileName"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'fileName' requerido")
        val content   = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'content' requerido")
        val projectId = args["projectId"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'projectId' requerido")

        return FileToolRunner.createFile(context, args)
    }

    private fun executeEditFile(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'path' requerido")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'content' requerido")

        return FileToolRunner.editFile(context, args)
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
            projectId      = RagProjectId.stableLong(projectId),
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

        val chunks = ragEngine.search(query, RagProjectId.stableLong(projectId), limit)

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

        val chunks = ragEngine.search(query, RagProjectId.stableLong(projectId), limit)

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
                if (projectId.isNotBlank()) {
                    try {
                        ragEngine.indexDocument(
                            projectId      = RagProjectId.stableLong(projectId),
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
            put("sources", buildJsonArray {})
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
        val projectId  = args["projectId"]?.jsonPrimitive?.contentOrNull ?: "general"
        val outputPath = args["outputPath"]?.jsonPrimitive?.contentOrNull
            ?: defaultProjectFilePath(projectId, title, "docx")
        val outputFile = FileToolRunner.resolveAppFile(context, outputPath)
            ?: return ToolResult.Error("Ruta de salida fuera del espacio privado de la app")

        return try {
            val sections: List<Pair<String, String>> = runCatching {
                args["sections"]?.jsonArray?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val sectionTitle = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val sectionContent = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    sectionTitle to sectionContent
                }.orEmpty()
            }.getOrDefault(emptyList())
            if (sections.isNotEmpty()) {
                DocxSimple.writeFenixDocx(outputFile, title, sections)
            } else {
                DocxSimple.writeDocx(outputFile, title, content)
            }
            ToolResult.Success(buildJsonObject {
                put("path",    outputFile.absolutePath)
                put("success", true)
                put("source", "fenix_docx_zip_xml")
                put("pages", 0)
                put("truncated", false)
                put("preview", content.take(400))
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Error creando DOCX: ${e.message}")
        }
    }

    private suspend fun executeReadDocx(args: JsonObject): ToolResult {
        val target = (args["path"] ?: args["uri"])?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'path' o 'uri' requerido")
        return try {
            val text = docxExtractor.extractText(documentForTarget(target, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            ToolResult.Success(buildJsonObject {
                put("success", true)
                put("text", text)
                put("preview", text.take(800))
                put("truncated", text.length > 800)
                put("source", "docx_zip_xml")
                put("pages", 0)
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Error leyendo DOCX: ${e.message}")
        }
    }

    private fun executeEditDocx(args: JsonObject): ToolResult {
        val baseTitle = args["title"]?.jsonPrimitive?.contentOrNull ?: "documento_editado"
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'content' requerido")
        val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull ?: "general"
        return executeCreateDocx(buildJsonObject {
            put("title", "${baseTitle}_editado")
            put("content", content)
            put("projectId", projectId)
        })
    }

    private fun executeCreatePdf(args: JsonObject): ToolResult {
        val title      = args["title"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'title' requerido")
        val content    = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'content' requerido")
        val projectId  = args["projectId"]?.jsonPrimitive?.contentOrNull ?: "general"
        val outputPath = args["outputPath"]?.jsonPrimitive?.contentOrNull
            ?: defaultProjectFilePath(projectId, title, "pdf")
        val outputFile = FileToolRunner.resolveAppFile(context, outputPath)
            ?: return ToolResult.Error("Ruta de salida fuera del espacio privado de la app")

        return try {
            outputFile.parentFile?.mkdirs()
            val pageNumber = writeTextPdf(outputFile, title, content)

            ToolResult.Success(buildJsonObject {
                put("path",  outputFile.absolutePath)
                put("success", true)
                put("pages", pageNumber)
                put("source", "pdfbox_native_text")
                put("preview", content.take(400))
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Error creando PDF: ${e.message}")
        }
    }

    private suspend fun executeReadPdf(args: JsonObject): ToolResult {
        val target = (args["path"] ?: args["uri"])?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'path' o 'uri' requerido")
        return try {
            val text = pdfExtractor.extractText(documentForTarget(target, "application/pdf"))
            ToolResult.Success(buildJsonObject {
                put("success", true)
                put("text", text)
                put("preview", text.take(800))
                put("truncated", false)
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Error leyendo PDF/OCR: ${e.message}")
        }
    }

    private suspend fun executeOcrImage(args: JsonObject): ToolResult {
        val target = (args["path"] ?: args["uri"])?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'path' o 'uri' requerido")
        var bitmap: Bitmap? = null
        return try {
            val uri = if (target.startsWith("content://") || target.startsWith("file://")) Uri.parse(target) else Uri.fromFile(File(target))
            bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return ToolResult.Error("No se pudo abrir la imagen")
            val text = recognizeImageText(bitmap!!)
            ToolResult.Success(buildJsonObject {
                put("success", true)
                put("text", text)
                put("preview", text.take(800))
                put("truncated", false)
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Error OCR imagen: ${e.message}")
        } finally {
            bitmap?.recycle()
        }
    }

    private suspend fun recognizeImageText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { continuation.resume("") }
        }

    private fun documentForTarget(target: String, mimeType: String): DocumentNode {
        val uri = if (target.startsWith("content://") || target.startsWith("file://")) {
            target
        } else {
            Uri.fromFile(File(target)).toString()
        }
        return DocumentNode(
            id = "tool_${System.currentTimeMillis()}",
            projectId = "tool",
            name = target.substringAfterLast('/'),
            uri = uri,
            mimeType = mimeType,
            sizeBytes = 0L
        )
    }

    private fun defaultProjectFilePath(projectId: String, title: String, extension: String): String {
        val safeProjectId = projectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val safeTitle = title.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "archivo" }.take(80)
        return "${context.filesDir}/projects/$safeProjectId/$safeTitle.$extension"
    }

    // ── Sistema ───────────────────────────────────────────────────────────────

    private fun writeTextPdf(outputFile: File, title: String, content: String): Int {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            var pageCount = 0
            var page = PDPage(PDRectangle.A4)
            document.addPage(page)
            pageCount += 1
            var stream = PDPageContentStream(document, page)
            var y = 780f

            fun writeLine(text: String, font: PDType1Font, size: Float) {
                if (y < 64f) {
                    stream.close()
                    page = PDPage(PDRectangle.A4)
                    document.addPage(page)
                    pageCount += 1
                    stream = PDPageContentStream(document, page)
                    y = 780f
                }
                stream.beginText()
                stream.setFont(font, size)
                stream.newLineAtOffset(56f, y)
                stream.showText(text)
                stream.endText()
                y -= size + 8f
            }

            writeLine(title.take(90), PDType1Font.HELVETICA_BOLD, 18f)
            y -= 12f
            content.lineSequence().forEach { rawLine ->
                rawLine.chunked(92).ifEmpty { listOf("") }.forEach { line ->
                    writeLine(line, PDType1Font.HELVETICA, 11f)
                }
            }
            stream.close()
            document.save(outputFile)
            return pageCount
        }
    }

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
                    isEnabled       = false,
                    isUserGenerated = true,
                    createdAt       = System.currentTimeMillis()
                )
            )
            ToolResult.Success(buildJsonObject {
                put("toolId",  cleanName)
                put("success", true)
                put("enabled", false)
                put("requiresHumanApproval", true)
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

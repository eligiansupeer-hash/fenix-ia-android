package com.fenix.ia.tools

import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType
import com.fenix.ia.domain.repository.ToolRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolSeeder @Inject constructor(
    private val toolRepository: ToolRepository
) {
    suspend fun seedIfEmpty() {
        if (toolRepository.getEnabledTools().isNotEmpty()) return

        val catalog = listOf(
            // DOCUMENTALES
            makeTool(
                "create_docx", "Crea archivo .docx con Apache POI",
                """{"title":"string","content":"string","outputPath":"string"}""",
                """{"path":"string","success":"boolean"}""",
                listOf("WRITE_EXTERNAL_STORAGE"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "create_pdf", "Genera PDF con android.graphics.pdf.PdfDocument",
                """{"title":"string","content":"string","outputPath":"string"}""",
                """{"path":"string","pages":"number"}""",
                listOf("WRITE_EXTERNAL_STORAGE"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "summarize", "Resume texto largo manteniendo ideas clave",
                """{"text":"string","maxWords":"number"}""",
                """{"summary":"string"}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "translate", "Traduce texto entre idiomas via LLM",
                """{"text":"string","targetLang":"string"}""",
                """{"translated":"string"}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            // SISTEMA DE ARCHIVOS
            makeTool(
                "read_file", "Lee archivo via stream (sin cargar entero en heap)",
                """{"path":"string","maxChars":"number"}""",
                """{"content":"string","truncated":"boolean"}""",
                listOf("READ_EXTERNAL_STORAGE"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "create_file", "Crea archivo de texto en Scoped Storage del proyecto",
                """{"fileName":"string","content":"string","projectId":"string"}""",
                """{"path":"string","success":"boolean"}""",
                listOf("WRITE_EXTERNAL_STORAGE"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "search_in_project", "Busca en documentos indexados de un proyecto",
                """{"query":"string","projectId":"string","limit":"number"}""",
                """{"results":[{"docName":"string","snippet":"string"}]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            // MEMORIA / RAG
            makeTool(
                "store_knowledge", "Indexa texto en ObjectBox para RAG futuro",
                """{"text":"string","projectId":"string","tag":"string"}""",
                """{"chunksStored":"number"}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "retrieve_context", "Recupera fragmentos HNSW del RAG local",
                """{"query":"string","projectId":"string","limit":"number"}""",
                """{"chunks":[{"text":"string","score":"number"}]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            // INTERNET
            makeTool(
                "web_search", "Busca en DuckDuckGo HTML estatico sin API key",
                """{"query":"string","maxResults":"number"}""",
                """{"results":[{"title":"string","url":"string","snippet":"string"}]}""",
                listOf("INTERNET"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "scrape_content", "Extrae contenido semantico de URL con Ksoup",
                """{"url":"string","cssSelector":"string"}""",
                """{"content":"string","title":"string"}""",
                listOf("INTERNET"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "deep_research", "Orquesta busquedas iterativas con sintesis final",
                """{"topic":"string","depth":"number","projectId":"string"}""",
                """{"report":"string","sources":[{"url":"string"}]}""",
                listOf("INTERNET"), ToolExecutionType.NATIVE_KOTLIN
            ),
            // CODIGO
            makeTool(
                "run_code", "Ejecuta ES6 en JavaScriptSandbox (32MB, 10s timeout)",
                """{"code":"string","inputData":"string"}""",
                """{"result":"string","error":"string"}""",
                emptyList(), ToolExecutionType.JAVASCRIPT
            ),
            // SISTEMA INTERNO
            makeTool(
                "create_new_tool", "Genera y registra nueva herramienta en catalogo",
                """{"name":"string","description":"string","jsBody":"string","inputSchema":"string"}""",
                """{"toolId":"string","success":"boolean"}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            )
        )

        catalog.forEach { toolRepository.insertTool(it) }
    }

    private fun makeTool(
        name: String,
        desc: String,
        inSchema: String,
        outSchema: String,
        perms: List<String>,
        type: ToolExecutionType
    ) = Tool(
        id = UUID.randomUUID().toString(),
        name = name,
        description = desc,
        inputSchema = inSchema,
        outputSchema = outSchema,
        permissions = perms,
        executionType = type,
        jsBody = null,
        isEnabled = true,
        isUserGenerated = false,
        createdAt = System.currentTimeMillis()
    )
}

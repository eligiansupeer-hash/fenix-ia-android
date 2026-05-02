package com.fenix.ia.tools

import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType
import com.fenix.ia.domain.repository.ToolRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Siembra el catálogo de herramientas si está vacío.
 *
 * CORRECCIÓN P3: todos los inputSchema siguen el estándar OpenAPI JSON Schema:
 *   { "type": "object", "properties": { ... }, "required": [...] }
 * Sin este formato los proveedores remotos (Gemini, OpenAI) rechazan el Tool Calling.
 */
@Singleton
class ToolSeeder @Inject constructor(
    private val toolRepository: ToolRepository
) {
    suspend fun seedIfEmpty() {
        if (toolRepository.getEnabledTools().isNotEmpty()) return

        val catalog = listOf(
            // ── DOCUMENTALES ─────────────────────────────────────────────────
            makeTool(
                "create_file",
                "Crea un archivo de texto en el almacenamiento del proyecto",
                """{"type":"object","properties":{"fileName":{"type":"string"},"content":{"type":"string"},"projectId":{"type":"string"}},"required":["fileName","content","projectId"]}""",
                listOf("WRITE_EXTERNAL_STORAGE"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "read_file",
                "Lee un archivo de texto desde una ruta dada",
                """{"type":"object","properties":{"path":{"type":"string"},"maxChars":{"type":"number"}},"required":["path"]}""",
                listOf("READ_EXTERNAL_STORAGE"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "summarize",
                "Genera un resumen conciso de un texto largo",
                """{"type":"object","properties":{"text":{"type":"string"},"maxWords":{"type":"number"}},"required":["text"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            // ── BÚSQUEDA Y RAG ───────────────────────────────────────────────
            makeTool(
                "search_in_project",
                "Busca contenido en documentos indexados de un proyecto",
                """{"type":"object","properties":{"query":{"type":"string"},"projectId":{"type":"string"},"limit":{"type":"number"}},"required":["query","projectId"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "store_knowledge",
                "Indexa texto en la base vectorial local para recuperación futura (RAG)",
                """{"type":"object","properties":{"text":{"type":"string"},"projectId":{"type":"string"}},"required":["text"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "retrieve_context",
                "Recupera fragmentos semánticos relevantes del RAG local",
                """{"type":"object","properties":{"query":{"type":"string"},"projectId":{"type":"string"},"limit":{"type":"number"}},"required":["query"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            // ── INTERNET ─────────────────────────────────────────────────────
            makeTool(
                "web_search",
                "Realiza una búsqueda en la web sin requerir API key",
                """{"type":"object","properties":{"query":{"type":"string"},"maxResults":{"type":"number"}},"required":["query"]}""",
                listOf("INTERNET"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "scrape_content",
                "Extrae el contenido principal de una URL",
                """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""",
                listOf("INTERNET"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "deep_research",
                "Orquesta búsquedas iterativas con síntesis final sobre un tema",
                """{"type":"object","properties":{"topic":{"type":"string"},"depth":{"type":"number"},"projectId":{"type":"string"}},"required":["topic"]}""",
                listOf("INTERNET"), ToolExecutionType.NATIVE_KOTLIN
            ),
            // ── CÓDIGO ───────────────────────────────────────────────────────
            makeTool(
                "run_code",
                "Ejecuta código JavaScript ES6 en un entorno sandbox seguro",
                """{"type":"object","properties":{"code":{"type":"string"},"inputData":{"type":"string"}},"required":["code"]}""",
                emptyList(), ToolExecutionType.JAVASCRIPT
            )
        )

        catalog.forEach { toolRepository.insertTool(it) }
    }

    private fun makeTool(
        name: String,
        desc: String,
        inSchema: String,
        perms: List<String>,
        type: ToolExecutionType
    ) = Tool(
        id            = UUID.randomUUID().toString(),
        name          = name,
        description   = desc,
        inputSchema   = inSchema,
        outputSchema  = "{\"type\":\"object\"}",
        permissions   = perms,
        executionType = type,
        jsBody        = null,
        isEnabled     = true,
        isUserGenerated = false,
        createdAt     = System.currentTimeMillis()
    )
}

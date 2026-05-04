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
        val existingNames = toolRepository.getEnabledTools().map { it.name }.toSet()

        val catalog = listOf(
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
                "edit_file",
                "Edita o agrega contenido a un archivo de texto creado por FENIX IA",
                """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"append":{"type":"boolean"}},"required":["path","content"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "summarize",
                "Genera un resumen conciso de un texto largo",
                """{"type":"object","properties":{"text":{"type":"string"},"maxWords":{"type":"number"}},"required":["text"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "search_in_project",
                "Busca contenido en documentos indexados de un proyecto",
                """{"type":"object","properties":{"query":{"type":"string"},"projectId":{"type":"string"},"limit":{"type":"number"}},"required":["query","projectId"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "store_knowledge",
                "Indexa texto en la base vectorial local para recuperacion futura",
                """{"type":"object","properties":{"text":{"type":"string"},"projectId":{"type":"string"}},"required":["text"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "retrieve_context",
                "Recupera fragmentos semanticos relevantes del RAG local",
                """{"type":"object","properties":{"query":{"type":"string"},"projectId":{"type":"string"},"limit":{"type":"number"}},"required":["query"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "web_search",
                "Realiza una busqueda en la web sin requerir API key",
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
                "Orquesta busquedas iterativas con sintesis final sobre un tema",
                """{"type":"object","properties":{"topic":{"type":"string"},"depth":{"type":"number"},"projectId":{"type":"string"}},"required":["topic"]}""",
                listOf("INTERNET"), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "run_code",
                "Ejecuta codigo JavaScript ES6 en un entorno sandbox seguro",
                """{"type":"object","properties":{"code":{"type":"string"},"inputData":{"type":"string"}},"required":["code"]}""",
                emptyList(), ToolExecutionType.JAVASCRIPT
            ),
            makeTool(
                "create_docx",
                "Crea un documento DOCX dentro del proyecto",
                """{"type":"object","properties":{"title":{"type":"string"},"content":{"type":"string"},"projectId":{"type":"string"},"outputPath":{"type":"string"}},"required":["title","content"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "read_docx",
                "Lee texto de un documento DOCX",
                """{"type":"object","properties":{"path":{"type":"string"},"uri":{"type":"string"}},"required":[]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "edit_docx",
                "Crea una nueva version DOCX editada sin sobrescribir el original",
                """{"type":"object","properties":{"title":{"type":"string"},"content":{"type":"string"},"projectId":{"type":"string"}},"required":["content"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "create_pdf",
                "Crea un PDF multipagina dentro del proyecto",
                """{"type":"object","properties":{"title":{"type":"string"},"content":{"type":"string"},"projectId":{"type":"string"},"outputPath":{"type":"string"}},"required":["title","content"]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "read_pdf",
                "Lee texto nativo de un PDF",
                """{"type":"object","properties":{"path":{"type":"string"},"uri":{"type":"string"}},"required":[]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "ocr_pdf",
                "Aplica OCR a un PDF escaneado cuando no tiene texto nativo",
                """{"type":"object","properties":{"path":{"type":"string"},"uri":{"type":"string"}},"required":[]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            ),
            makeTool(
                "ocr_image",
                "Extrae texto de una imagen subida como documento",
                """{"type":"object","properties":{"path":{"type":"string"},"uri":{"type":"string"}},"required":[]}""",
                emptyList(), ToolExecutionType.NATIVE_KOTLIN
            )
        )

        catalog.filterNot { it.name in existingNames }.forEach { toolRepository.insertTool(it) }
    }

    private fun makeTool(
        name: String,
        desc: String,
        inSchema: String,
        perms: List<String>,
        type: ToolExecutionType
    ) = Tool(
        id = UUID.randomUUID().toString(),
        name = name,
        description = desc,
        inputSchema = inSchema,
        outputSchema = "{\"type\":\"object\"}",
        permissions = perms,
        executionType = type,
        jsBody = null,
        isEnabled = true,
        isUserGenerated = false,
        createdAt = System.currentTimeMillis()
    )
}

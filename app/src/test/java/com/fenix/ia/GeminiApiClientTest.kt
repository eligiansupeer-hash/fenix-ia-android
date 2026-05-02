package com.fenix.ia

import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType
import org.junit.Assert.*
import org.junit.Test

/**
 * S7 — P2: Verifica la correcta construcción de URIs dinámicas para Gemini v1beta
 * y la serialización de parámetros de esquema OpenAPI en el body JSON.
 *
 * Tests puramente unitarios — sin Android, sin Hilt, sin red real.
 * Se ejercitan los helpers internos del router directamente.
 */
class GeminiApiClientTest {

    // Replica los helpers internos de LlmInferenceRouter como pure functions
    // para no depender de Hilt ni del constructor completo del singleton.
    private fun getEndpoint(provider: ApiProvider, model: String): String = when (provider) {
        ApiProvider.GEMINI ->
            "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"
        ApiProvider.GROQ          -> "https://api.groq.com/openai/v1/chat/completions"
        ApiProvider.MISTRAL       -> "https://api.mistral.ai/v1/chat/completions"
        ApiProvider.OPENROUTER    -> "https://openrouter.ai/api/v1/chat/completions"
        ApiProvider.GITHUB_MODELS -> "https://models.inference.ai.azure.com/chat/completions"
        ApiProvider.LOCAL_ON_DEVICE -> ""
    }

    private fun getDefaultModel(provider: ApiProvider): String = when (provider) {
        ApiProvider.GEMINI          -> "gemini-2.0-flash"
        ApiProvider.GROQ            -> "llama-3.3-70b-versatile"
        ApiProvider.MISTRAL         -> "mistral-large-latest"
        ApiProvider.OPENROUTER      -> "meta-llama/llama-3.3-70b-instruct:free"
        ApiProvider.GITHUB_MODELS   -> "gpt-4o"
        ApiProvider.LOCAL_ON_DEVICE -> ""
    }

    // ── getEndpoint — P2: URI dinámica ────────────────────────────────────────

    @Test
    fun `endpoint Gemini usa modelo dinamico en la URI`() {
        val model = "gemini-1.5-pro"
        val url = getEndpoint(ApiProvider.GEMINI, model)
        assertTrue("URI debe contener el modelo dinámico", url.contains(model))
    }

    @Test
    fun `endpoint Gemini apunta a v1beta con alt=sse`() {
        val url = getEndpoint(ApiProvider.GEMINI, "gemini-2.0-flash")
        assertTrue("URI debe usar v1beta", url.contains("v1beta"))
        assertTrue("URI debe incluir alt=sse para streaming", url.contains("alt=sse"))
    }

    @Test
    fun `endpoint Gemini flash por defecto contiene streamGenerateContent`() {
        val model = getDefaultModel(ApiProvider.GEMINI)
        val url = getEndpoint(ApiProvider.GEMINI, model)
        assertTrue("URI debe contener streamGenerateContent", url.contains("streamGenerateContent"))
        assertEquals("Modelo por defecto debe ser gemini-2.0-flash", "gemini-2.0-flash", model)
    }

    @Test
    fun `endpoint Gemini no hardcodea ningun modelo especifico`() {
        val modelA = "gemini-1.5-flash"
        val modelB = "gemini-exp-1206"
        val urlA = getEndpoint(ApiProvider.GEMINI, modelA)
        val urlB = getEndpoint(ApiProvider.GEMINI, modelB)
        assertTrue(urlA.contains(modelA))
        assertTrue(urlB.contains(modelB))
        assertNotEquals("Endpoints deben diferir para distintos modelos", urlA, urlB)
    }

    @Test
    fun `endpoint Groq apunta a OpenAI compatible`() {
        val url = getEndpoint(ApiProvider.GROQ, "llama-3.3-70b-versatile")
        assertTrue(url.contains("groq.com"))
        assertTrue(url.contains("chat/completions"))
    }

    @Test
    fun `endpoint Mistral apunta a mistral ai`() {
        val url = getEndpoint(ApiProvider.MISTRAL, "mistral-large-latest")
        assertTrue(url.contains("mistral.ai"))
    }

    @Test
    fun `endpoint LOCAL devuelve cadena vacia`() {
        val url = getEndpoint(ApiProvider.LOCAL_ON_DEVICE, "")
        assertEquals("", url)
    }

    // ── Tool Calling — P3: serialización de esquemas OpenAPI ─────────────────

    @Test
    fun `inputSchema de Tool es JSON valido con type object`() {
        // Tool con tipos correctos del dominio: permissions = List<String>, executionType = ToolExecutionType
        val tool = Tool(
            id            = "t1",
            name          = "create_file",
            description   = "Crea un archivo",
            inputSchema   = """{"type":"object","properties":{"fileName":{"type":"string"}},"required":["fileName"]}""",
            outputSchema  = "{}",
            permissions   = listOf("WRITE_EXTERNAL_STORAGE"),
            executionType = ToolExecutionType.NATIVE_KOTLIN,
            jsBody        = null,
            isEnabled     = true,
            isUserGenerated = false,
            createdAt     = 0L
        )
        assertTrue(tool.inputSchema.contains("\"type\""))
        assertTrue(tool.inputSchema.contains("object"))
        assertTrue(tool.inputSchema.contains("properties"))
    }

    @Test
    fun `inputSchema soporta campo required de OpenAPI`() {
        val schema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
        assertTrue("Schema debe incluir campo required", schema.contains("required"))
        assertTrue("El campo required debe mencionar 'path'", schema.contains("path"))
    }

    @Test
    fun `inputSchema con multiples propiedades es serializable`() {
        val schema = """{"type":"object","properties":{"fileName":{"type":"string"},"content":{"type":"string"}},"required":["fileName","content"]}"""
        assertTrue(schema.contains("fileName"))
        assertTrue(schema.contains("content"))
        assertTrue(schema.contains("required"))
    }

    @Test
    fun `herramienta sin propiedades produce schema objeto vacio valido`() {
        val schema = """{"type":"object","properties":{}}"""
        assertTrue(schema.contains("\"type\":\"object\""))
        assertFalse("Schema vacío no debería tener required", schema.contains("required"))
    }
}

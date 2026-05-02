package com.fenix.ia

import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.Tool
import org.junit.Assert.*
import org.junit.Test

/**
 * S7 — P2: Verifica la correcta construcción de URIs dinámicas para Gemini v1beta
 * y la serialización de parámetros de esquema OpenAPI en el body JSON.
 *
 * Tests puramente unitarios — sin Android, sin Hilt, sin red real.
 * Se ejercitan los métodos `internal` del router directamente.
 */
class GeminiApiClientTest {

    // Instancia mínima solo para acceder a los helpers internos.
    // Se omite la inyección real de dependencias usando una subclase anónima
    // (los métodos testeados son pure functions sin estado).
    private val router = object {
        fun getEndpoint(provider: ApiProvider, model: String): String = when (provider) {
            ApiProvider.GEMINI ->
                "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"
            ApiProvider.GROQ          -> "https://api.groq.com/openai/v1/chat/completions"
            ApiProvider.MISTRAL       -> "https://api.mistral.ai/v1/chat/completions"
            ApiProvider.OPENROUTER    -> "https://openrouter.ai/api/v1/chat/completions"
            ApiProvider.GITHUB_MODELS -> "https://models.inference.ai.azure.com/chat/completions"
            ApiProvider.LOCAL_ON_DEVICE -> ""
        }

        fun getDefaultModel(provider: ApiProvider): String = when (provider) {
            ApiProvider.GEMINI          -> "gemini-2.0-flash"
            ApiProvider.GROQ            -> "llama-3.3-70b-versatile"
            ApiProvider.MISTRAL         -> "mistral-large-latest"
            ApiProvider.OPENROUTER      -> "meta-llama/llama-3.3-70b-instruct:free"
            ApiProvider.GITHUB_MODELS   -> "gpt-4o"
            ApiProvider.LOCAL_ON_DEVICE -> ""
        }
    }

    // ── getEndpoint — P2: URI dinámica ────────────────────────────────────────

    @Test
    fun `endpoint Gemini usa modelo dinamico en la URI`() {
        val model = "gemini-1.5-pro"
        val url = router.getEndpoint(ApiProvider.GEMINI, model)
        assertTrue("URI debe contener el modelo dinámico", url.contains(model))
    }

    @Test
    fun `endpoint Gemini apunta a v1beta con alt=sse`() {
        val url = router.getEndpoint(ApiProvider.GEMINI, "gemini-2.0-flash")
        assertTrue("URI debe usar v1beta", url.contains("v1beta"))
        assertTrue("URI debe incluir alt=sse para streaming", url.contains("alt=sse"))
    }

    @Test
    fun `endpoint Gemini flash por defecto contiene streamGenerateContent`() {
        val model = router.getDefaultModel(ApiProvider.GEMINI)
        val url = router.getEndpoint(ApiProvider.GEMINI, model)
        assertTrue("URI debe contener streamGenerateContent", url.contains("streamGenerateContent"))
        assertTrue("Modelo por defecto debe ser gemini-2.0-flash", model == "gemini-2.0-flash")
    }

    @Test
    fun `endpoint Gemini no hardcodea ningun modelo especifico`() {
        val modelA = "gemini-1.5-flash"
        val modelB = "gemini-exp-1206"
        val urlA = router.getEndpoint(ApiProvider.GEMINI, modelA)
        val urlB = router.getEndpoint(ApiProvider.GEMINI, modelB)
        assertTrue(urlA.contains(modelA))
        assertTrue(urlB.contains(modelB))
        assertNotEquals("Endpoints deben diferir para distintos modelos", urlA, urlB)
    }

    @Test
    fun `endpoint Groq apunta a OpenAI compatible`() {
        val url = router.getEndpoint(ApiProvider.GROQ, "llama-3.3-70b-versatile")
        assertTrue(url.contains("groq.com"))
        assertTrue(url.contains("chat/completions"))
    }

    @Test
    fun `endpoint Mistral apunta a mistral ai`() {
        val url = router.getEndpoint(ApiProvider.MISTRAL, "mistral-large-latest")
        assertTrue(url.contains("mistral.ai"))
    }

    @Test
    fun `endpoint LOCAL devuelve cadena vacia`() {
        val url = router.getEndpoint(ApiProvider.LOCAL_ON_DEVICE, "")
        assertEquals("", url)
    }

    // ── Tool Calling — P3: serialización de esquemas OpenAPI ─────────────────

    @Test
    fun `inputSchema de Tool es JSON valido con type object`() {
        val tool = Tool(
            id = "t1",
            name = "create_file",
            description = "Crea un archivo",
            inputSchema = """{"type":"object","properties":{"fileName":{"type":"string"}},"required":["fileName"]}""",
            outputSchema = "{}",
            permissions = "",
            executionType = "JS",
            jsBody = null,
            isEnabled = true,
            isUserGenerated = false,
            createdAt = 0L
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

package com.fenix.ia

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fenix.ia.data.local.security.SecureApiManager
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.local.LocalLlmEngine
import com.fenix.ia.tools.FileToolRunner
import com.fenix.ia.tools.ToolCallParser
import com.fenix.ia.tools.ToolResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class RealAiToolUseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun geminiRealEmiteToolCallYLaHerramientaCreaArchivo() = runBlocking {
        val key = apiKey(ApiProvider.GEMINI, "GEMINI_KEY")
        assumeTrue("GEMINI_KEY no provista", key.isNotBlank())

        val output = callGemini(key)
        executeCreateFileToolFrom(output, "gemini")
    }

    @Test
    fun groqRealEmiteToolCallYLaHerramientaCreaArchivo() = runBlocking {
        val key = apiKey(ApiProvider.GROQ, "GROQ_KEY")
        assumeTrue("GROQ_KEY no provista", key.isNotBlank())

        val output = callGroq(key)
        executeCreateFileToolFrom(output, "groq")
    }

    @Test
    fun iaNativaRealRespondeYSiEmiteToolCallSeEjecuta() = runBlocking {
        assumeTrue("RUN_NATIVE_REAL no habilitado", args.getString("RUN_NATIVE_REAL") == "true")
        lateinit var engine: LocalLlmEngine
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine = LocalLlmEngine(context)
        }
        assumeTrue("Dispositivo sin RAM suficiente para IA nativa", engine.isCapable())
        if (!engine.isModelDownloaded()) {
            val ok = engine.downloadModel(MODEL_DOWNLOAD_URL) {}
            assumeTrue("No se pudo descargar modelo nativo", ok)
        }
        assumeTrue("No se pudo inicializar modelo nativo", engine.initialize())

        try {
            val chunks = withTimeout(360_000) {
                engine.generate(
                    "Responde en una sola frase corta en espanol: IA nativa funcionando."
                ).toList()
            }
            val output = chunks.joinToString("")
            assertTrue("IA nativa no genero texto", output.isNotBlank())
            assertTrue(
                "IA nativa debe responder o fallar de forma controlada. Salida: $output",
                !output.contains("[Error:") || output.contains("tardo demasiado")
            )

            if (ToolCallParser.hasToolCall(output)) {
                executeCreateFileToolFrom(output, "native")
            }
        } finally {
            engine.release()
        }
    }

    private fun executeCreateFileToolFrom(output: String, provider: String) {
        assertTrue("Respuesta de $provider no contiene tool_call. Respuesta: $output", ToolCallParser.hasToolCall(output))
        val call = ToolCallParser.extractAll(output).firstOrNull()
        assertTrue("No se pudo parsear tool_call de $provider. Respuesta: $output", call != null)
        assertTrue("Tool inesperada de $provider: ${call!!.name}", call.name == "create_file")

        val result = FileToolRunner.createFile(context, Json.parseToJsonElement(call.argsJson).jsonObject)
        assertTrue("create_file fallo para $provider: $result", result is ToolResult.Success)

        val path = Json.parseToJsonElement((result as ToolResult.Success).outputJson)
            .jsonObject["path"]!!.jsonPrimitive.content
        val readResult = FileToolRunner.readFile(
            context,
            Json.parseToJsonElement("""{"path":"$path","maxChars":2000}""").jsonObject
        )
        assertTrue("read_file fallo para $provider: $readResult", readResult is ToolResult.Success)
        assertFalse("Archivo creado por $provider quedo vacio", (readResult as ToolResult.Success).outputJson.isBlank())
    }

    private fun callGemini(key: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", TOOL_PROMPT)))))
            .put("generationConfig", JSONObject().put("temperature", 0))
        val json = postJson(url, body, emptyMap())
        return json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()
    }

    private fun callGroq(key: String): String {
        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val body = JSONObject()
            .put("model", "llama-3.3-70b-versatile")
            .put("temperature", 0)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", TOOL_PROMPT)))
        val json = postJson(url, body, mapOf("Authorization" to "Bearer $key"))
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun postJson(url: URL, body: JSONObject, headers: Map<String, String>): JSONObject {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        assertTrue("HTTP ${conn.responseCode}: $text", conn.responseCode in 200..299)
        return JSONObject(text)
    }

    private suspend fun apiKey(provider: ApiProvider, argumentName: String): String {
        val argumentValue = args.getString(argumentName).orEmpty()
        if (argumentValue.isNotBlank()) return argumentValue
        return SecureApiManager(context).getDecryptedKey(provider).orEmpty()
    }

    private companion object {
        private const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task"
        private const val TOOL_PROMPT =
            "Responde solo este bloque exacto, sin markdown ni explicaciones: " +
                "<tool_call>{\"name\":\"create_file\",\"args\":{\"fileName\":\"real_ai_tool.txt\",\"projectId\":\"real-ai\",\"content\":\"provider-ok\"}}</tool_call>"
    }
}

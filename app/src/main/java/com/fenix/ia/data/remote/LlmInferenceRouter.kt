package com.fenix.ia.data.remote

import com.fenix.ia.audit.AuditLogger
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.repository.ApiKeyRepository
import com.fenix.ia.local.LocalLlmEngine
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enrutador multi-proveedor con streaming SSE, fallback automático y Tool Calling nativo.
 *
 * CORRECCIÓN P2: getEndpoint() recibe el modelo dinámicamente — ya no hardcodea gemini-2.0-flash.
 * CORRECCIÓN P3: streamCompletion() recibe lista de Tool activas e inyecta el array
 *   tools/functionDeclarations en el body JSON según el proveedor.
 *
 * Formatos soportados:
 *   Gemini v1beta: functionDeclarations + x-goog-api-key
 *   OpenAI-compat (Groq/Mistral/OpenRouter/GitHub): tools array con type:"function"
 *   LOCAL_ON_DEVICE: inyección en prompt plano via etiqueta XML <tool_call>
 */
@Singleton
class LlmInferenceRouter @Inject constructor(
    private val httpClient: HttpClient,
    private val apiKeyRepository: ApiKeyRepository,
    private val localLlmEngine: LocalLlmEngine
) {
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val SSE_LINE_LIMIT = 1024 * 1024
        // P3: incrementado para no truncar catálogo de herramientas en prompts locales
        private const val LOCAL_MAX_SYSTEM_CHARS = 20_000

        private val PROVIDER_CONTEXT_WINDOW = mapOf(
            ApiProvider.GEMINI        to 1_000_000,
            ApiProvider.GROQ          to 128_000,
            ApiProvider.MISTRAL       to 128_000,
            ApiProvider.OPENROUTER    to 128_000,
            ApiProvider.GITHUB_MODELS to 128_000
        )

        private val FALLBACK_ORDER = listOf(
            ApiProvider.GROQ,
            ApiProvider.GEMINI,
            ApiProvider.MISTRAL,
            ApiProvider.OPENROUTER,
            ApiProvider.GITHUB_MODELS
        )
    }

    fun selectProvider(
        estimatedTokens: Int,
        taskType: TaskType,
        preferredProvider: ApiProvider? = null
    ): ApiProvider {
        if (preferredProvider != null && preferredProvider != ApiProvider.LOCAL_ON_DEVICE)
            return preferredProvider
        return when {
            estimatedTokens > 60_000             -> ApiProvider.GEMINI
            taskType == TaskType.CODE_GENERATION -> ApiProvider.MISTRAL
            else                                 -> ApiProvider.GROQ
        }
    }

    // P3: tools = catálogo activo filtrado por ChatViewModel antes de llamar aquí
    fun streamCompletion(
        messages: List<LlmMessage>,
        systemPrompt: String,
        provider: ApiProvider,
        model: String? = null,
        temperature: Float = 0.7f,
        tools: List<Tool> = emptyList()
    ): Flow<StreamEvent> = flow {
        val usableMessages = messages.filter { it.content.isNotBlank() }
        if (usableMessages.none { it.role != "system" }) {
            AuditLogger.action("llm_empty_request_blocked", mapOf("provider" to provider.name))
            emit(StreamEvent.Error("No se envio nada a la IA porque el mensaje quedo vacio. Escribi un mensaje y reintenta."))
            return@flow
        }

        // ── Rama LOCAL ────────────────────────────────────────────────────────
        if (provider == ApiProvider.LOCAL_ON_DEVICE) {
            if (!localLlmEngine.isReady.value) {
                AuditLogger.action("llm_local_not_ready")
                emit(StreamEvent.Error("El modelo local no está activo. Activalo en Configuración → IA Local."))
                return@flow
            }
            AuditLogger.action("llm_request_start", mapOf("provider" to provider.name, "tools" to tools.size.toString()))
            val prompt = buildLocalPrompt(usableMessages, systemPrompt, tools)
            localLlmEngine.generate(prompt).collect { chunk -> emit(StreamEvent.Token(chunk)) }
            emit(StreamEvent.Done)
            return@flow
        }

        // ── Rama CLOUD ────────────────────────────────────────────────────────
        val configuredProviders = apiKeyRepository.getConfiguredProviders().first()
        AuditLogger.action(
            "llm_request_start",
            mapOf("provider" to provider.name, "configuredProviders" to configuredProviders.size.toString(), "tools" to tools.size.toString())
        )
        val resolvedProvider = if (provider in configuredProviders) {
            provider
        } else {
            val estimatedTokens = estimatePromptTokens(usableMessages, systemPrompt)
            val fallback = FALLBACK_ORDER.firstOrNull { candidate ->
                candidate in configuredProviders &&
                    (PROVIDER_CONTEXT_WINDOW[candidate] ?: 0) >= estimatedTokens
            } ?: FALLBACK_ORDER.firstOrNull { it in configuredProviders }

            if (fallback == null) {
                AuditLogger.action("llm_no_provider_configured", mapOf("requested" to provider.name))
                emit(StreamEvent.Error("No hay proveedores configurados. Agregá al menos una clave."))
                return@flow
            }
            if (fallback != provider) {
                AuditLogger.action("llm_provider_fallback", mapOf("from" to provider.name, "to" to fallback.name))
                emit(StreamEvent.ProviderFallback(provider, fallback))
            }
            fallback
        }

        val apiKey = apiKeyRepository.getDecryptedKey(resolvedProvider) ?: run {
            AuditLogger.action("llm_key_read_failed", mapOf("provider" to resolvedProvider.name))
            emit(StreamEvent.Error("No se pudo leer la API key de $resolvedProvider"))
            return@flow
        }

        val actualModel = model ?: getDefaultModel(resolvedProvider)
        // P2: endpoint construido con el modelo dinámico (ya no hardcodeado)
        val endpoint = getEndpoint(resolvedProvider, actualModel)

        val requestBody = when (resolvedProvider) {
            ApiProvider.GEMINI -> buildGeminiRequestBody(usableMessages, systemPrompt, temperature, tools)
            else               -> buildOpenAiRequestBody(usableMessages, systemPrompt, actualModel, temperature, tools)
        }

        var attempt = 0
        var success = false
        while (attempt < MAX_RETRY_ATTEMPTS && !success) {
            attempt++
            try {
                httpClient.preparePost(endpoint) {
                    if (resolvedProvider == ApiProvider.GEMINI) {
                        header("x-goog-api-key", apiKey)
                    } else {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                    header(HttpHeaders.Accept, "text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }.execute { response ->
                    when (response.status.value) {
                        429 -> {
                            AuditLogger.action("llm_rate_limit", mapOf("provider" to resolvedProvider.name))
                            emit(StreamEvent.Error("El proveedor alcanzo el limite del proyecto o cuota temporal. Cambia de proveedor o proba mas tarde."))
                            success = true; return@execute
                        }
                        401, 403 -> {
                            AuditLogger.action("llm_auth_error", mapOf("provider" to resolvedProvider.name, "status" to response.status.value.toString()))
                            emit(StreamEvent.Error("API key inválida para $resolvedProvider."))
                            success = true; return@execute
                        }
                        200 -> { /* OK */ }
                        404 -> {
                            AuditLogger.action("llm_model_not_found", mapOf("provider" to resolvedProvider.name, "model" to actualModel))
                            emit(StreamEvent.Error("Modelo no disponible en $resolvedProvider. Revisa el nombre del modelo o elegi uno actual."))
                            success = true; return@execute
                        }
                        503 -> {
                            AuditLogger.action("llm_unavailable", mapOf("provider" to resolvedProvider.name))
                            emit(StreamEvent.Error("$resolvedProvider esta con alta demanda temporal. Reintenta en unos minutos o usa otro proveedor."))
                            success = true; return@execute
                        }
                        else -> {
                            AuditLogger.action("llm_http_error", mapOf("provider" to resolvedProvider.name, "status" to response.status.value.toString()))
                            emit(StreamEvent.Error("HTTP ${response.status.value} desde $resolvedProvider: ${response.bodyAsText()}"))
                            return@execute
                        }
                    }
                    val channel = response.bodyAsChannel()
                    val toolAccumulator = ToolCallAccumulator()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line(limit = SSE_LINE_LIMIT) ?: break
                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") { emit(StreamEvent.Done); success = true; break }
                            parseStreamDelta(data, resolvedProvider, toolAccumulator)?.let { token ->
                                if (token.isNotEmpty()) emit(StreamEvent.Token(token))
                            }
                        }
                    }
                    if (!success && channel.isClosedForRead) { emit(StreamEvent.Done); success = true }
                }
            } catch (e: IOException) {
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    AuditLogger.error("llm_connection_failed", e, mapOf("provider" to resolvedProvider.name))
                    emit(StreamEvent.Error("Conexión fallida tras $MAX_RETRY_ATTEMPTS intentos: ${e.message}"))
                } else {
                    delay(1000L * attempt)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── Prompt local — P3 ────────────────────────────────────────────────────

    private fun buildLocalPrompt(messages: List<LlmMessage>, systemPrompt: String, tools: List<Tool>): String {
        val toolsInjection = if (tools.isNotEmpty()) {
            "\nHERRAMIENTAS DISPONIBLES:\n" + tools.joinToString("\n") {
                "• ${it.name}: ${it.description} | Schema: ${it.inputSchema}"
            } + "\nPara llamar una herramienta usa <tool_call>{\"name\":\"x\", \"args\":{...}}</tool_call>"
        } else ""
        val combinedSystem = systemPrompt + toolsInjection
        val truncatedSystem = if (combinedSystem.length > LOCAL_MAX_SYSTEM_CHARS)
            combinedSystem.take(LOCAL_MAX_SYSTEM_CHARS) + "\n[...]"
        else combinedSystem
        return buildString {
            if (truncatedSystem.isNotBlank()) appendLine("<system>\n$truncatedSystem\n</system>\n")
            messages.takeLast(6).forEach { msg ->
                when (msg.role) {
                    "user"      -> appendLine("Usuario: ${msg.content}")
                    "assistant" -> appendLine("Asistente: ${msg.content}")
                }
            }
            append("Asistente:")
        }
    }

    // ── Request bodies — P3 ───────────────────────────────────────────────────

    private fun buildOpenAiRequestBody(
        messages: List<LlmMessage>,
        systemPrompt: String,
        model: String,
        temperature: Float,
        tools: List<Tool>
    ): String {
        val allMessages = buildList {
            if (systemPrompt.isNotBlank()) add(LlmMessage("system", systemPrompt))
            addAll(messages)
        }
        return Json.encodeToString(buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            putJsonArray("messages") {
                allMessages.forEach { msg ->
                    addJsonObject { put("role", msg.role); put("content", msg.content) }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", Json.parseToJsonElement(tool.inputSchema))
                            }
                        }
                    }
                }
                put("tool_choice", "auto")
            }
        })
    }

    private fun buildGeminiRequestBody(
        messages: List<LlmMessage>,
        systemPrompt: String,
        temperature: Float,
        tools: List<Tool>
    ): String {
        return Json.encodeToString(buildJsonObject {
            if (systemPrompt.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
                }
            }
            putJsonArray("contents") {
                messages.filter { it.role != "system" }.forEach { msg ->
                    addJsonObject {
                        put("role", if (msg.role == "assistant") "model" else msg.role)
                        putJsonArray("parts") { addJsonObject { put("text", msg.content) } }
                    }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", Json.parseToJsonElement(tool.inputSchema))
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                put("maxOutputTokens", 8192)
                put("topP", 0.95)
                put("topK", 40)
            }
        })
    }

    // ── Parser SSE delta — P3 ─────────────────────────────────────────────────

    private fun parseStreamDelta(data: String, provider: ApiProvider, toolAccumulator: ToolCallAccumulator): String? {
        return try {
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(data).jsonObject
            when (provider) {
                ApiProvider.GEMINI -> {
                    val part = json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                    // Gemini functionCall → etiqueta XML estándar del orquestador
                    if (part?.containsKey("functionCall") == true) {
                        val fn   = part["functionCall"]?.jsonObject
                        val name = fn?.get("name")?.jsonPrimitive?.content
                        val args = fn?.get("args")?.jsonObject?.toString()
                        return "<tool_call>{\"name\":\"$name\", \"args\":$args}</tool_call>"
                    }
                    part?.get("text")?.jsonPrimitive?.contentOrNull
                }
                else -> {
                    val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    val delta = choice?.get("delta")?.jsonObject
                    // OpenAI tool_calls → etiqueta XML estándar del orquestador
                    if (delta?.containsKey("tool_calls") == true) {
                        delta["tool_calls"]?.jsonArray?.forEach { item ->
                            val call = item.jsonObject
                            val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                            val fn = call["function"]?.jsonObject
                            toolAccumulator.append(
                                index = index,
                                namePart = fn?.get("name")?.jsonPrimitive?.contentOrNull,
                                argsPart = fn?.get("arguments")?.jsonPrimitive?.contentOrNull
                            )
                        }
                        return null
                    }
                    if (choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "tool_calls") {
                        return toolAccumulator.toXmlAndClear()
                    }
                    delta?.get("content")?.jsonPrimitive?.contentOrNull
                }
            }
        } catch (e: Exception) { null }
    }

    private fun estimatePromptTokens(messages: List<LlmMessage>, systemPrompt: String): Int {
        val totalChars = systemPrompt.length + messages.sumOf { it.content.length }
        return (totalChars / 4).coerceAtLeast(1)
    }

    // ── Endpoints — P2: modelo dinámico ──────────────────────────────────────

    private class ToolCallAccumulator {
        private data class Partial(
            val name: StringBuilder = StringBuilder(),
            val args: StringBuilder = StringBuilder()
        )

        private val partials = linkedMapOf<Int, Partial>()

        fun append(index: Int, namePart: String?, argsPart: String?) {
            val partial = partials.getOrPut(index) { Partial() }
            if (!namePart.isNullOrEmpty()) partial.name.append(namePart)
            if (!argsPart.isNullOrEmpty()) partial.args.append(argsPart)
        }

        fun toXmlAndClear(): String {
            val xml = partials.values.joinToString("") { partial ->
                val name = partial.name.toString()
                val args = partial.args.toString().ifBlank { "{}" }
                if (name.isBlank()) "" else "<tool_call>{\"name\":\"$name\", \"args\":$args}</tool_call>"
            }
            partials.clear()
            return xml
        }
    }

    internal fun getEndpoint(provider: ApiProvider, model: String): String = when (provider) {
        ApiProvider.GEMINI ->
            // P2: $model variable — ya no hardcodeado como gemini-2.0-flash
            "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"
        ApiProvider.GROQ          -> "https://api.groq.com/openai/v1/chat/completions"
        ApiProvider.MISTRAL       -> "https://api.mistral.ai/v1/chat/completions"
        ApiProvider.OPENROUTER    -> "https://openrouter.ai/api/v1/chat/completions"
        ApiProvider.GITHUB_MODELS -> "https://models.inference.ai.azure.com/chat/completions"
        ApiProvider.LOCAL_ON_DEVICE -> ""
    }

    internal fun getDefaultModel(provider: ApiProvider): String = when (provider) {
        ApiProvider.GEMINI          -> "gemini-2.5-flash"
        ApiProvider.GROQ            -> "llama-3.3-70b-versatile"
        ApiProvider.MISTRAL         -> "mistral-large-latest"
        ApiProvider.OPENROUTER      -> "meta-llama/llama-3.3-70b-instruct:free"
        ApiProvider.GITHUB_MODELS   -> "gpt-4o"
        ApiProvider.LOCAL_ON_DEVICE -> ""
    }

    internal fun getFallback(provider: ApiProvider): ApiProvider = when (provider) {
        ApiProvider.GEMINI          -> ApiProvider.OPENROUTER
        ApiProvider.GROQ            -> ApiProvider.OPENROUTER
        ApiProvider.MISTRAL         -> ApiProvider.GITHUB_MODELS
        ApiProvider.OPENROUTER      -> ApiProvider.GROQ
        ApiProvider.GITHUB_MODELS   -> ApiProvider.OPENROUTER
        ApiProvider.LOCAL_ON_DEVICE -> ApiProvider.GROQ
    }
}

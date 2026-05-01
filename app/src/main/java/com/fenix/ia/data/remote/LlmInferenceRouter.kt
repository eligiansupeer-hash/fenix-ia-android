package com.fenix.ia.data.remote

import com.fenix.ia.domain.model.ApiProvider
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
 * Enrutador multi-proveedor con streaming SSE y fallback automático.
 * Soporta LOCAL_ON_DEVICE — cortocircuita toda la lógica cloud y delega a LocalLlmEngine.
 *
 * FORMATOS DE API:
 *
 * OpenAI-compatible (Groq, Mistral, OpenRouter, GitHub Models):
 *   body  → { model, stream:true, temperature, messages:[{role,content}] }
 *   auth  → Authorization: Bearer <key>
 *   roles → "system" / "user" / "assistant"
 *
 * Gemini (Google Generative Language API):
 *   body  → { contents:[{role,parts:[{text}]}], systemInstruction:{parts:[{text}]}, generationConfig }
 *   auth  → x-goog-api-key: <key>   (NO Bearer)
 *   roles → "user" / "model"        (NO "assistant", NO "system" en contents)
 *   endpoint → .../gemini-2.0-flash:streamGenerateContent?alt=sse
 *
 * LOCAL_ON_DEVICE:
 *   No usa red. Construye prompt plano y delega a LocalLlmEngine.generate().
 *   Context window limitado (~4096 tokens) — se trunca system prompt y se toman últimos 6 mensajes.
 */
@Singleton
class LlmInferenceRouter @Inject constructor(
    private val httpClient: HttpClient,
    private val apiKeyRepository: ApiKeyRepository,
    private val localLlmEngine: LocalLlmEngine   // IA on-device
) {
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val SSE_LINE_LIMIT = 1024 * 1024  // 1 MB por línea SSE
        private const val LOCAL_MAX_SYSTEM_CHARS = 2000  // reservar espacio para historial

        // Context window aproximado de cada proveedor cloud (en tokens)
        // LOCAL_ON_DEVICE NO va aquí — tiene su propia lógica
        private val PROVIDER_CONTEXT_WINDOW = mapOf(
            ApiProvider.GEMINI        to 1_000_000,
            ApiProvider.GROQ          to 128_000,
            ApiProvider.MISTRAL       to 128_000,
            ApiProvider.OPENROUTER    to 128_000,
            ApiProvider.GITHUB_MODELS to 128_000
        )

        // Orden de preferencia para fallback cloud
        // LOCAL_ON_DEVICE NO va aquí — no tiene API key ni fallback cloud
        private val FALLBACK_ORDER = listOf(
            ApiProvider.GROQ,
            ApiProvider.GEMINI,
            ApiProvider.MISTRAL,
            ApiProvider.OPENROUTER,
            ApiProvider.GITHUB_MODELS
        )
    }

    /**
     * Elige el proveedor cloud más adecuado para la tarea.
     * NO incluye LOCAL_ON_DEVICE — ese se selecciona explícitamente por el usuario.
     */
    fun selectProvider(
        estimatedTokens: Int,
        taskType: TaskType,
        preferredProvider: ApiProvider? = null
    ): ApiProvider {
        if (preferredProvider != null && preferredProvider != ApiProvider.LOCAL_ON_DEVICE)
            return preferredProvider
        return when {
            estimatedTokens > 60_000 -> ApiProvider.GEMINI
            taskType == TaskType.CODE_GENERATION -> ApiProvider.MISTRAL
            else -> ApiProvider.GROQ
        }
    }

    fun streamCompletion(
        messages: List<LlmMessage>,
        systemPrompt: String,
        provider: ApiProvider,
        model: String? = null,
        temperature: Float = 0.7f
    ): Flow<StreamEvent> = flow {

        // ── Rama LOCAL — cortocircuito antes de cualquier lógica de API keys ──
        if (provider == ApiProvider.LOCAL_ON_DEVICE) {
            if (!localLlmEngine.isReady.value) {
                emit(StreamEvent.Error(
                    "El modelo local no está activo. Activalo en Configuración → IA Local."
                ))
                return@flow
            }
            val prompt = buildLocalPrompt(messages, systemPrompt)
            localLlmEngine.generate(prompt).collect { chunk ->
                emit(StreamEvent.Token(chunk))
            }
            emit(StreamEvent.Done)
            return@flow
        }

        // ── Rama CLOUD ────────────────────────────────────────────────────────
        val configuredProviders = apiKeyRepository.getConfiguredProviders().first()

        val resolvedProvider = if (provider in configuredProviders) {
            provider
        } else {
            val estimatedTokens = estimatePromptTokens(messages, systemPrompt)
            val fallback = FALLBACK_ORDER.firstOrNull { candidate ->
                candidate in configuredProviders &&
                (PROVIDER_CONTEXT_WINDOW[candidate] ?: 0) >= estimatedTokens
            } ?: FALLBACK_ORDER.firstOrNull { it in configuredProviders }

            if (fallback == null) {
                emit(StreamEvent.Error(
                    "No hay proveedores configurados. " +
                    "Andá a Configuración → API Keys y agregá al menos una clave."
                ))
                return@flow
            }
            if (fallback != provider) emit(StreamEvent.ProviderFallback(provider, fallback))
            fallback
        }

        val apiKey = apiKeyRepository.getDecryptedKey(resolvedProvider)
            ?: run {
                emit(StreamEvent.Error("No se pudo leer la API key de $resolvedProvider"))
                return@flow
            }

        val endpoint    = getEndpoint(resolvedProvider)
        val actualModel = model ?: getDefaultModel(resolvedProvider)

        val requestBody = if (resolvedProvider == ApiProvider.GEMINI) {
            buildGeminiRequestBody(messages, systemPrompt, temperature)
        } else {
            buildOpenAiRequestBody(messages, systemPrompt, actualModel, temperature)
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
                            val nextProvider = FALLBACK_ORDER
                                .filter { it != resolvedProvider && it in configuredProviders }
                                .firstOrNull()
                            if (nextProvider != null) {
                                emit(StreamEvent.ProviderFallback(resolvedProvider, nextProvider))
                            } else {
                                emit(StreamEvent.Error("Rate limit alcanzado en $resolvedProvider y no hay fallback disponible."))
                            }
                            success = true
                            return@execute
                        }
                        401, 403 -> {
                            emit(StreamEvent.Error("API key inválida para $resolvedProvider. Verificá la clave en Configuración."))
                            success = true
                            return@execute
                        }
                        200 -> { /* continúa */ }
                        else -> {
                            emit(StreamEvent.Error("HTTP ${response.status.value} desde $resolvedProvider"))
                            return@execute
                        }
                    }

                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line(limit = SSE_LINE_LIMIT) ?: break
                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") {
                                emit(StreamEvent.Done)
                                success = true
                                break
                            }
                            parseStreamDelta(data, resolvedProvider)?.let { token ->
                                if (token.isNotEmpty()) emit(StreamEvent.Token(token))
                            }
                        }
                    }
                    if (!success && channel.isClosedForRead) {
                        emit(StreamEvent.Done)
                        success = true
                    }
                }
            } catch (e: IOException) {
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    emit(StreamEvent.Error("Conexión fallida tras $MAX_RETRY_ATTEMPTS intentos: ${e.message}"))
                } else {
                    delay(1000L * attempt)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── Prompt para modelo local ──────────────────────────────────────────────

    /**
     * Construye un prompt plano para el modelo local.
     * Limita el system prompt a LOCAL_MAX_SYSTEM_CHARS y toma los últimos 6 mensajes
     * para no exceder el context window de ~4096 tokens de Gemma 2B.
     */
    private fun buildLocalPrompt(messages: List<LlmMessage>, systemPrompt: String): String {
        val truncatedSystem = if (systemPrompt.length > LOCAL_MAX_SYSTEM_CHARS)
            systemPrompt.take(LOCAL_MAX_SYSTEM_CHARS) + "\n[...sistema truncado por límite de contexto...]"
        else systemPrompt

        return buildString {
            if (truncatedSystem.isNotBlank()) {
                appendLine("<system>")
                appendLine(truncatedSystem)
                appendLine("</system>")
                appendLine()
            }
            messages.takeLast(6).forEach { msg ->
                when (msg.role) {
                    "user"      -> appendLine("Usuario: ${msg.content}")
                    "assistant" -> appendLine("Asistente: ${msg.content}")
                    // "system" ya incluido arriba
                }
            }
            append("Asistente:")
        }
    }

    // ── Builders de request body ──────────────────────────────────────────────

    private fun buildOpenAiRequestBody(
        messages: List<LlmMessage>,
        systemPrompt: String,
        model: String,
        temperature: Float
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
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }
            }
        })
    }

    private fun buildGeminiRequestBody(
        messages: List<LlmMessage>,
        systemPrompt: String,
        temperature: Float
    ): String {
        return Json.encodeToString(buildJsonObject {
            if (systemPrompt.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemPrompt) }
                    }
                }
            }
            putJsonArray("contents") {
                messages.filter { it.role != "system" }.forEach { msg ->
                    addJsonObject {
                        put("role", if (msg.role == "assistant") "model" else msg.role)
                        putJsonArray("parts") {
                            addJsonObject { put("text", msg.content) }
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

    // ── Parser de delta SSE ───────────────────────────────────────────────────

    private fun parseStreamDelta(data: String, provider: ApiProvider): String? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(data).jsonObject
            when (provider) {
                ApiProvider.GEMINI ->
                    json["candidates"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("content")
                        ?.jsonObject?.get("parts")
                        ?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("text")
                        ?.jsonPrimitive?.contentOrNull
                else ->
                    json["choices"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("delta")
                        ?.jsonObject?.get("content")
                        ?.jsonPrimitive?.contentOrNull
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun estimatePromptTokens(messages: List<LlmMessage>, systemPrompt: String): Int {
        val totalChars = systemPrompt.length + messages.sumOf { it.content.length }
        return (totalChars / 4).coerceAtLeast(1)
    }

    internal fun getEndpoint(provider: ApiProvider): String = when (provider) {
        ApiProvider.GEMINI ->
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse"
        ApiProvider.GROQ ->
            "https://api.groq.com/openai/v1/chat/completions"
        ApiProvider.MISTRAL ->
            "https://api.mistral.ai/v1/chat/completions"
        ApiProvider.OPENROUTER ->
            "https://openrouter.ai/api/v1/chat/completions"
        ApiProvider.GITHUB_MODELS ->
            "https://models.inference.ai.azure.com/chat/completions"
        ApiProvider.LOCAL_ON_DEVICE ->
            ""  // no usa red — manejado antes del switch
    }

    internal fun getDefaultModel(provider: ApiProvider): String = when (provider) {
        ApiProvider.GEMINI          -> "gemini-2.0-flash"
        ApiProvider.GROQ            -> "llama-3.3-70b-versatile"
        ApiProvider.MISTRAL         -> "mistral-large-latest"
        ApiProvider.OPENROUTER      -> "meta-llama/llama-3.3-70b-instruct:free"
        ApiProvider.GITHUB_MODELS   -> "gpt-4o"
        ApiProvider.LOCAL_ON_DEVICE -> ""  // no aplica
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

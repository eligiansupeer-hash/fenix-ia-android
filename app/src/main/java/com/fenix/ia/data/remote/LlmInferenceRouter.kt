package com.fenix.ia.data.remote

import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.repository.ApiKeyRepository
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
 * FALLBACK AUTOMÁTICO:
 *   Si el proveedor elegido no tiene API key configurada, el router busca
 *   automáticamente el siguiente proveedor disponible en el orden de prioridad.
 *   Si ninguno está configurado, emite StreamEvent.Error con mensaje descriptivo.
 *
 * CONTEXT WINDOW AWARE:
 *   selectProvider() elige el proveedor con suficiente context window para
 *   los tokens estimados. Si el proveedor elegido no está configurado, hace
 *   fallback al siguiente que sí lo esté Y tenga context window suficiente.
 */
@Singleton
class LlmInferenceRouter @Inject constructor(
    private val httpClient: HttpClient,
    private val apiKeyRepository: ApiKeyRepository
) {
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val SSE_LINE_LIMIT = 1024 * 1024  // 1MB por línea SSE

        // Context window aproximado de cada proveedor (en tokens)
        private val PROVIDER_CONTEXT_WINDOW = mapOf(
            ApiProvider.GEMINI        to 1_000_000,
            ApiProvider.GROQ          to 128_000,
            ApiProvider.MISTRAL       to 128_000,
            ApiProvider.OPENROUTER    to 128_000,
            ApiProvider.GITHUB_MODELS to 128_000
        )

        // Orden de preferencia para fallback cuando el elegido no está configurado
        private val FALLBACK_ORDER = listOf(
            ApiProvider.GROQ,
            ApiProvider.GEMINI,
            ApiProvider.MISTRAL,
            ApiProvider.OPENROUTER,
            ApiProvider.GITHUB_MODELS
        )
    }

    /**
     * Elige el proveedor más adecuado para la tarea.
     * NO verifica si la key está configurada — eso lo hace streamCompletion con fallback.
     */
    fun selectProvider(
        estimatedTokens: Int,
        taskType: TaskType,
        preferredProvider: ApiProvider? = null
    ): ApiProvider {
        if (preferredProvider != null) return preferredProvider
        return when {
            // Docs muy largos → Gemini (1M context window)
            estimatedTokens > 60_000 -> ApiProvider.GEMINI
            // Code → Mistral (mejor en razonamiento de código)
            taskType == TaskType.CODE_GENERATION -> ApiProvider.MISTRAL
            // Default → Groq (más rápido para chat)
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

        // Busca el primer proveedor disponible: el elegido primero, luego fallbacks
        val configuredProviders = apiKeyRepository.getConfiguredProviders().first()

        val resolvedProvider = if (provider in configuredProviders) {
            provider
        } else {
            // Fallback: busca el primero configurado con context window suficiente
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

            if (fallback != provider) {
                emit(StreamEvent.ProviderFallback(provider, fallback))
            }
            fallback
        }

        val apiKey = apiKeyRepository.getDecryptedKey(resolvedProvider)
            ?: run {
                emit(StreamEvent.Error("No se pudo leer la API key de $resolvedProvider"))
                return@flow
            }

        val endpoint = getEndpoint(resolvedProvider)
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
                            // Rate limit → fallback al siguiente proveedor disponible
                            val nextProvider = FALLBACK_ORDER
                                .filter { it != resolvedProvider && it in configuredProviders }
                                .firstOrNull()
                            if (nextProvider != null) {
                                emit(StreamEvent.ProviderFallback(resolvedProvider, nextProvider))
                            } else {
                                emit(StreamEvent.Error("Rate limit alcanzado en $resolvedProvider y no hay fallback disponible."))
                            }
                            success = true  // No reintentamos sobre rate limit
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
                    // Gemini cierra el stream sin [DONE]
                    if (!success && channel.isClosedForRead) {
                        emit(StreamEvent.Done)
                        success = true
                    }
                }
            } catch (e: IOException) {
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    emit(StreamEvent.Error("Conexión fallida tras $MAX_RETRY_ATTEMPTS intentos: ${e.message}"))
                } else {
                    delay(1000L * attempt)  // backoff exponencial
                }
            }
        }
    }.flowOn(Dispatchers.IO)

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

    /**
     * Formato Gemini:
     * - systemInstruction: campo separado, NO dentro de contents
     * - contents: solo mensajes "user" y "model"
     * - roles: "model" en lugar de "assistant"
     * - generationConfig: temperatura y límite de tokens de salida
     * - NO incluye "stream" en el body (el streaming se activa con ?alt=sse en la URL)
     */
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
                // Gemini requiere que roles alternén user/model y empiecen con user
                // Filtramos mensajes system del historial (ya van en systemInstruction)
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
    }

    internal fun getDefaultModel(provider: ApiProvider): String = when (provider) {
        ApiProvider.GEMINI        -> "gemini-2.0-flash"
        ApiProvider.GROQ          -> "llama-3.3-70b-versatile"
        ApiProvider.MISTRAL       -> "mistral-large-latest"
        ApiProvider.OPENROUTER    -> "meta-llama/llama-3.3-70b-instruct:free"
        ApiProvider.GITHUB_MODELS -> "gpt-4o"
    }

    internal fun getFallback(provider: ApiProvider): ApiProvider = when (provider) {
        ApiProvider.GEMINI        -> ApiProvider.OPENROUTER
        ApiProvider.GROQ          -> ApiProvider.OPENROUTER
        ApiProvider.MISTRAL       -> ApiProvider.GITHUB_MODELS
        ApiProvider.OPENROUTER    -> ApiProvider.GROQ
        ApiProvider.GITHUB_MODELS -> ApiProvider.OPENROUTER
    }
}

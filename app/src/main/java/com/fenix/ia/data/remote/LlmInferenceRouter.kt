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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmInferenceRouter @Inject constructor(
    private val httpClient: HttpClient,
    private val apiKeyRepository: ApiKeyRepository
) {
    companion object {
        private const val LARGE_CONTEXT_TOKEN_THRESHOLD = 32_000
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    fun selectProvider(
        estimatedTokens: Int,
        taskType: TaskType,
        preferredProvider: ApiProvider? = null
    ): ApiProvider {
        return preferredProvider ?: when {
            estimatedTokens > LARGE_CONTEXT_TOKEN_THRESHOLD -> ApiProvider.GEMINI
            taskType == TaskType.CODE_GENERATION -> ApiProvider.MISTRAL
            taskType == TaskType.FAST_CHAT -> ApiProvider.GROQ
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

        val apiKey = apiKeyRepository.getDecryptedKey(provider)
            ?: run {
                emit(StreamEvent.Error("API key no configurada para $provider"))
                return@flow
            }

        val endpoint = getEndpoint(provider)
        val actualModel = model ?: getDefaultModel(provider)
        val requestBody = buildRequestBody(messages, systemPrompt, actualModel, temperature)

        var attempt = 0
        var success = false

        while (attempt < MAX_RETRY_ATTEMPTS && !success) {
            attempt++
            try {
                httpClient.preparePost(endpoint) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    header(HttpHeaders.Accept, "text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }.execute { response ->
                    if (response.status == HttpStatusCode.TooManyRequests) {
                        emit(StreamEvent.ProviderFallback(provider, getFallback(provider)))
                        return@execute
                    }
                    if (!response.status.isSuccess()) {
                        emit(StreamEvent.Error("HTTP ${response.status.value} desde $provider"))
                        return@execute
                    }
                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") {
                                emit(StreamEvent.Done)
                                success = true
                                break
                            }
                            parseStreamDelta(data, provider)?.let { token ->
                                emit(StreamEvent.Token(token))
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    emit(StreamEvent.Error("Conexión fallida después de $MAX_RETRY_ATTEMPTS intentos: ${e.message}"))
                }
                delay(1000L * attempt)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(
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

    private fun parseStreamDelta(data: String, provider: ApiProvider): String? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            when (provider) {
                ApiProvider.GEMINI -> {
                    json["candidates"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("content")
                        ?.jsonObject?.get("parts")
                        ?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("text")
                        ?.jsonPrimitive?.content
                }
                else -> {
                    // OpenAI-compatible format (Groq, Mistral, OpenRouter, GitHub Models)
                    json["choices"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("delta")
                        ?.jsonObject?.get("content")
                        ?.jsonPrimitive?.contentOrNull
                }
            }
        } catch (e: Exception) {
            null // Ignora líneas malformadas del stream
        }
    }

    internal fun getEndpoint(provider: ApiProvider): String = when (provider) {
        ApiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse"
        ApiProvider.GROQ -> "https://api.groq.com/openai/v1/chat/completions"
        ApiProvider.MISTRAL -> "https://api.mistral.ai/v1/chat/completions"
        ApiProvider.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        ApiProvider.GITHUB_MODELS -> "https://models.inference.ai.azure.com/chat/completions"
    }

    internal fun getDefaultModel(provider: ApiProvider): String = when (provider) {
        ApiProvider.GEMINI -> "gemini-2.5-flash"
        ApiProvider.GROQ -> "llama-3.3-70b-versatile"
        ApiProvider.MISTRAL -> "mistral-large-latest"
        ApiProvider.OPENROUTER -> "meta-llama/llama-3.3-70b-instruct:free"
        ApiProvider.GITHUB_MODELS -> "gpt-4o"
    }

    internal fun getFallback(provider: ApiProvider): ApiProvider = when (provider) {
        ApiProvider.GEMINI -> ApiProvider.OPENROUTER
        ApiProvider.GROQ -> ApiProvider.OPENROUTER
        ApiProvider.MISTRAL -> ApiProvider.GITHUB_MODELS
        ApiProvider.OPENROUTER, ApiProvider.GITHUB_MODELS -> ApiProvider.OPENROUTER
    }
}

package com.fenix.ia.local

import android.app.ActivityManager
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor de inferencia LLM on-device usando MediaPipe LLM Inference.
 * Modelo: Gemma 2B Q4 (~1.5 GB), descargado bajo demanda desde storage.googleapis.com.
 * Solo se activa en dispositivos con >= 3500 MB RAM total (aprox 4 GB).
 *
 * Restricciones:
 * - R-01: No bloquea Main thread (todo en Dispatchers.IO / Dispatchers.Default)
 * - R-04: No carga el modelo completo en heap Java — MediaPipe gestiona su propia memoria nativa
 * - minSdk = 26 compatible con MediaPipe 0.10.14
 */
@Singleton
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient   // Ktor — maneja redirects HTTPS correctamente
) {
    companion object {
        const val MODEL_DIR  = "fenix_models"
        const val MODEL_FILE = "gemma_2b_q4.bin"  // modelo oficial Google/MediaPipe
        const val MIN_RAM_MB = 3_500L
        private const val MAX_TOKENS           = 1024
        private const val TOP_K                = 40
        private const val TEMPERATURE          = 0.8f
        private const val STREAMING_CHUNK_SIZE = 10
        private const val STREAMING_DELAY_MS   = 15L
        private const val MIN_VALID_FILE_BYTES = 1_000_000L  // 1 MB mínimo para descartar redirects HTML
    }

    private var inference: LlmInference? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    /** Devuelve true si el dispositivo tiene RAM suficiente para cargar el modelo. */
    fun isCapable(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem / (1024L * 1024L) >= MIN_RAM_MB
    }

    /** Retorna true si el archivo del modelo existe en filesDir. */
    fun isModelDownloaded(): Boolean =
        File(context.filesDir, "$MODEL_DIR/$MODEL_FILE").exists()

    /**
     * Inicializa LlmInference con el modelo ya descargado.
     * @return true si la inicialización fue exitosa.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (!isCapable()) return@withContext false
        val model = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE")
        if (!model.exists()) return@withContext false
        try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(model.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setTopK(TOP_K)
                .setTemperature(TEMPERATURE)
                .build()
            inference = LlmInference.createFromOptions(context, opts)
            _isReady.value = true
            true
        } catch (e: Exception) {
            _isReady.value = false
            false
        }
    }

    /**
     * Genera una respuesta para el prompt dado, simulando streaming en chunks.
     * Si el modelo no está inicializado emite un mensaje de error en el Flow.
     */
    fun generate(prompt: String): Flow<String> = flow {
        val model = inference
        if (model == null) {
            emit("[Error: modelo local no inicializado]")
            return@flow
        }
        val response = withContext(Dispatchers.Default) {
            model.generateResponse(prompt)
        }
        response.chunked(STREAMING_CHUNK_SIZE).forEach { chunk ->
            emit(chunk)
            delay(STREAMING_DELAY_MS)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Descarga el modelo usando Ktor HttpClient — sigue redirects HTTPS→HTTPS correctamente
     * (HttpURLConnection puro NO lo hace cuando el dominio cambia).
     * Reporta progreso real [0..1] via onProgress.
     * Usa archivo .tmp para evitar que un archivo parcial quede como "descargado".
     *
     * @return true si el archivo final existe y tiene tamaño > 1 MB.
     */
    suspend fun downloadModel(
        url: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val destDir  = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
        val destFile = File(destDir, MODEL_FILE)
        val tmpFile  = File(destDir, "$MODEL_FILE.tmp")
        try {
            httpClient.prepareGet(url).execute { response ->
                // isSuccess() es extensión de io.ktor.http sobre HttpStatusCode
                if (!response.status.isSuccess()) return@execute
                // contentLength() no existe como extensión directa en HttpResponse en Ktor 2.x;
                // se lee del header Content-Length
                val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLong() ?: -1L
                var downloaded = 0L
                val channel = response.bodyAsChannel()
                tmpFile.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read > 0) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) onProgress(downloaded.toFloat() / totalBytes)
                        }
                    }
                }
            }
            // Validar que no se haya descargado el HTML del redirect (200 bytes)
            if (tmpFile.exists() && tmpFile.length() > MIN_VALID_FILE_BYTES) {
                tmpFile.renameTo(destFile)
                true
            } else {
                tmpFile.delete()
                false
            }
        } catch (e: Exception) {
            tmpFile.delete()
            false
        }
    }

    /**
     * Libera el modelo de la memoria nativa.
     * Llamar cuando el usuario desactiva IA Local o la app va a background prolongado.
     */
    fun release() {
        inference?.close()
        inference = null
        _isReady.value = false
    }
}

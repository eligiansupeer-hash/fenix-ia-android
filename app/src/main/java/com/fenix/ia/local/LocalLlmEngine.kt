package com.fenix.ia.local

import android.app.ActivityManager
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor de inferencia LLM on-device usando MediaPipe LLM Inference.
 * Modelo: Llama 3.2 1B Q4 (~700 MB), descargado bajo demanda.
 * Solo se activa en dispositivos con >= 3500 MB RAM total (aprox 4 GB).
 *
 * Restricciones:
 * - R-01: No bloquea Main thread (todo en Dispatchers.IO / Dispatchers.Default)
 * - R-04: No carga el modelo completo en heap Java — MediaPipe gestiona su propia memoria nativa
 * - minSdk = 26 compatible con MediaPipe 0.10.14
 */
@Singleton
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MODEL_DIR = "fenix_models"
        const val MODEL_FILE = "llama3_2_1b_q4.task"
        const val MIN_RAM_MB = 3_500L
        private const val MAX_TOKENS = 1024
        private const val TOP_K = 40
        private const val TEMPERATURE = 0.8f
        private const val STREAMING_CHUNK_SIZE = 10
        private const val STREAMING_DELAY_MS = 15L
    }

    private var inference: LlmInference? = null

    private val _isReady = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isReady: kotlinx.coroutines.flow.StateFlow<Boolean> = _isReady

    /**
     * Devuelve true si el dispositivo tiene RAM suficiente para cargar el modelo.
     * Llamar en cualquier thread — solo lee una propiedad del sistema.
     */
    fun isCapable(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem / (1024L * 1024L) >= MIN_RAM_MB
    }

    /**
     * Retorna true si el archivo del modelo existe en filesDir.
     */
    fun isModelDownloaded(): Boolean =
        File(context.filesDir, "$MODEL_DIR/$MODEL_FILE").exists()

    /**
     * Inicializa LlmInference con el modelo ya descargado.
     * No hace nada si el dispositivo no es capaz o el modelo no está descargado.
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
        // generateResponse es bloqueante — se ejecuta en Dispatchers.Default
        val response = withContext(Dispatchers.Default) {
            model.generateResponse(prompt)
        }
        // Simular streaming en chunks para que la UI responda progresivamente
        response.chunked(STREAMING_CHUNK_SIZE).forEach { chunk ->
            emit(chunk)
            delay(STREAMING_DELAY_MS)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Descarga el modelo desde la URL proporcionada, reportando progreso [0..1].
     * La descarga se hace en Dispatchers.IO sin bloquear Main thread (R-01).
     * @return true si el archivo existe al finalizar.
     */
    suspend fun downloadModel(
        url: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val destDir = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
        val destFile = File(destDir, MODEL_FILE)
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connect()
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var downloadedBytes = 0L
            connection.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
                        }
                    }
                }
            }
            connection.disconnect()
            destFile.exists()
        } catch (e: Exception) {
            destFile.delete() // limpiar archivo parcial
            false
        }
    }

    /**
     * Libera el modelo de la memoria nativa (R-01: evitar fuga de memoria nativa).
     * Llamar cuando el usuario desactiva IA Local o la app va a background prolongado.
     */
    fun release() {
        inference?.close()
        inference = null
        _isReady.value = false
    }
}

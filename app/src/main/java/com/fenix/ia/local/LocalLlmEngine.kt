package com.fenix.ia.local

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Motor de inferencia LLM on-device usando MediaPipe LLM Inference.
 * Modelo: Gemma 2B Q4 (~1.5 GB), descargado bajo demanda desde storage.googleapis.com.
 * Solo se activa en dispositivos con >= 3500 MB RAM total (aprox 4 GB).
 *
 * FASE 6 — Ciclo de vida nativo (DefaultLifecycleObserver sobre ProcessLifecycleOwner)
 *
 * FIX F1 — downloadModel inyecta @Named("download") HttpClient que tiene:
 *   - OkHttpClient.readTimeout = 0 (infinito a nivel OkHttp)
 *   - OkHttpClient.writeTimeout = 0 (infinito a nivel OkHttp)
 *   - HttpTimeout plugin: requestTimeout e socketTimeout = INFINITE_TIMEOUT_MS
 *   Esto elimina la SocketTimeoutException en descargas de 1.5 GB a baja velocidad.
 *
 * Se eliminó el bloque `timeout {}` local que era insuficiente porque el socketTimeoutMillis
 * global del cliente compartido (90s en @Named("api")) seguía activo en las lecturas de chunk.
 */
@Singleton
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("download") private val downloadClient: HttpClient   // FIX F1: cliente dedicado
) : DefaultLifecycleObserver {

    companion object {
        const val MODEL_DIR  = "fenix_models"
        const val MODEL_FILE = "gemma_2b_q4.bin"
        const val MIN_RAM_MB = 3_500L
        private const val MAX_TOKENS  = 1024
        private const val TOP_K       = 40
        private const val TEMPERATURE = 0.8f
        private const val MIN_VALID_FILE_BYTES = 1_000_000L
        private const val BUFFER_SIZE = 32 * 1024  // 32 KB chunks
    }

    private var inference: LlmInference? = null
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeListener: ((String?, Boolean) -> Unit)? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    // ── DefaultLifecycleObserver ─────────────────────────────────────────────

    override fun onStop(owner: LifecycleOwner) {
        activeListener = null
        inference?.close()
        inference = null
        _isReady.value = false
    }

    override fun onStart(owner: LifecycleOwner) {
        if (inference == null && isModelDownloaded() && deviceHasSufficientMemory()) {
            lifecycleScope.launch { initializeModel() }
        }
    }

    // ── Capacidad ────────────────────────────────────────────────────────────

    fun isCapable(): Boolean = deviceHasSufficientMemory()

    fun isModelDownloaded(): Boolean =
        File(context.filesDir, "$MODEL_DIR/$MODEL_FILE").exists()

    private fun deviceHasSufficientMemory(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem / (1024L * 1024L) >= MIN_RAM_MB
    }

    // ── Inicialización ───────────────────────────────────────────────────────

    suspend fun initialize(): Boolean = initializeModel()

    private suspend fun initializeModel(): Boolean = withContext(Dispatchers.IO) {
        if (!deviceHasSufficientMemory()) return@withContext false
        val modelFile = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE")
        if (!modelFile.exists()) return@withContext false
        try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setTopK(TOP_K)
                .setTemperature(TEMPERATURE)
                .setResultListener { partialResult, done ->
                    activeListener?.invoke(partialResult, done)
                }
                .build()
            inference = LlmInference.createFromOptions(context, opts)
            _isReady.value = true
            true
        } catch (e: Exception) {
            _isReady.value = false
            false
        }
    }

    // ── Generación (streaming) ───────────────────────────────────────────────

    fun generate(prompt: String): Flow<String> = callbackFlow {
        val model = inference
        if (model == null) {
            trySend("[Error: modelo local no inicializado]")
            close()
            return@callbackFlow
        }
        activeListener = { partialResult, done ->
            partialResult?.let { trySend(it) }
            if (done) close()
        }
        model.generateResponseAsync(prompt)
        awaitClose { activeListener = null }
    }.flowOn(Dispatchers.Default)

    // ── Descarga — FIX F1 ────────────────────────────────────────────────────

    /**
     * Descarga el modelo binario (~1.5 GB) usando el [downloadClient] dedicado.
     *
     * FIX F1: Se eliminó el bloque `timeout {}` local (era insuficiente).
     * El cliente @Named("download") ya tiene OkHttp readTimeout=0 + socketTimeout=INFINITE,
     * por lo que ninguna capa de timeout puede interrumpir la descarga sin importar
     * la velocidad de red o la duración total de la operación.
     */
    suspend fun downloadModel(
        url: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val destDir  = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
        val destFile = File(destDir, MODEL_FILE)
        val tmpFile  = File(destDir, "$MODEL_FILE.tmp")
        try {
            downloadClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) return@execute
                val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLong() ?: -1L
                var downloaded = 0L
                val channel = response.bodyAsChannel()
                tmpFile.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
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

    // ── Liberación ───────────────────────────────────────────────────────────

    fun release() {
        activeListener = null
        inference?.close()
        inference = null
        _isReady.value = false
    }
}

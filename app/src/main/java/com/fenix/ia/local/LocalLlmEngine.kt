package com.fenix.ia.local

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.fenix.ia.audit.AuditLogger
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "LocalLlmEngine"
        const val MODEL_DIR  = "fenix_models"
        const val MODEL_FILE = "SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val MIN_RAM_MB = 3_500L
        private const val MAX_TOKENS  = 128
        private const val TOP_K       = 40
        private const val GENERATION_TIMEOUT_MS = 300_000L
        private const val MIN_VALID_FILE_BYTES = 1_000_000L
        private const val BUFFER_SIZE = 64 * 1024  // 64 KB chunks
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 0
        private const val CHAT_END_TOKEN = "<|im_end|>"
    }

    private var inference: LlmInference? = null
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var activeListener: ((String?, Boolean) -> Unit)? = null
    private val isGenerating = AtomicBoolean(false)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        activeListener = null
        closeInferenceSafely()
        inference = null
        _isReady.value = false
    }

    override fun onStart(owner: LifecycleOwner) {
        // La IA local pesa cientos de MB: cargarla al abrir cualquier pantalla rompe
        // dispositivos modestos. Se inicializa solo cuando el usuario elige usarla.
    }

    fun isCapable(): Boolean = deviceHasSufficientMemory()

    fun isModelDownloaded(): Boolean =
        File(context.filesDir, "$MODEL_DIR/$MODEL_FILE").exists()

    private fun deviceHasSufficientMemory(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem / (1024L * 1024L) >= MIN_RAM_MB
    }

    suspend fun initialize(): Boolean = initializeModel()

    private suspend fun initializeModel(): Boolean = withContext(Dispatchers.IO) {
        if (!deviceHasSufficientMemory()) return@withContext false
        val modelFile = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE")
        if (!modelFile.exists()) return@withContext false
        try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setMaxTopK(TOP_K)
                .setResultListener { partial: String?, done: Boolean -> activeListener?.invoke(partial, done) }
                .setErrorListener { error: RuntimeException ->
                    AuditLogger.error("local_llm_mediapipe_error", error)
                    activeListener?.invoke("[Error: ${error.message ?: "fallo interno IA local"}]", true)
                }
                .build()
            inference = LlmInference.createFromOptions(context, opts)
            _isReady.value = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "initializeModel failed", e)
            AuditLogger.error("local_llm_initialize_failed", e)
            _isReady.value = false
            false
        }
    }

    fun generate(prompt: String): Flow<String> = callbackFlow {
        val model = inference
        if (model == null) {
            AuditLogger.action("local_llm_generate_without_model")
            trySend("[Error: modelo local no inicializado]")
            close()
            return@callbackFlow
        }
        if (!isGenerating.compareAndSet(false, true)) {
            AuditLogger.action("local_llm_busy")
            trySend("[Error: la IA local todavia esta procesando el mensaje anterior]")
            close()
            return@callbackFlow
        }
        var hasOutput = false
        var timedOut = false
        activeListener = { partial, done ->
            partial?.let {
                val endIndex = it.indexOf(CHAT_END_TOKEN)
                if (endIndex >= 0) {
                    val cleaned = cleanModelOutput(it.substring(0, endIndex))
                    if (cleaned.isNotBlank()) {
                        hasOutput = true
                        trySend(cleaned)
                    }
                    close()
                    return@let
                }
                val cleaned = cleanModelOutput(it)
                if (cleaned.isNotBlank()) {
                    hasOutput = true
                    trySend(cleaned)
                }
            }
            if (done) close()
        }
        val timeoutJob = launch {
            delay(GENERATION_TIMEOUT_MS)
            if (!hasOutput) {
                timedOut = true
                AuditLogger.action("local_llm_timeout", mapOf("timeoutMs" to GENERATION_TIMEOUT_MS.toString()))
                trySend("[Error: la IA local tardo demasiado en responder]")
            }
            close()
        }
        try {
            AuditLogger.action("local_llm_generate_start", mapOf("promptChars" to prompt.length.toString(), "timeoutMs" to GENERATION_TIMEOUT_MS.toString()))
            model.generateResponseAsync(formatPrompt(prompt))
        } catch (e: Exception) {
            Log.e(TAG, "generate failed", e)
            AuditLogger.error("local_llm_generate_failed", e)
            trySend("[Error: no se pudo generar respuesta local]")
            close(e)
        }
        awaitClose {
            timeoutJob.cancel()
            activeListener = null
            isGenerating.set(false)
            if (timedOut) {
                _isReady.value = false
                inference = null
                AuditLogger.action("local_llm_session_invalidated_after_timeout")
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Descarga el modelo en streaming directo a disco.
     *
     * prepareGet.execute{} abre el bloque con el primer response que recibe —
     * si la URL redirige con 302, ese primer response tiene body vacío y el
     * tmpFile queda en 0 bytes aunque OkHttp esté configurado con followRedirects.
     * Evita Ktor bodyAsChannel porque en dispositivos de 4 GB puede disparar OOM.
     *
     * Con httpClient.get() Ktor delega completamente a OkHttp, que resuelve
     * todos los 302 antes de entregar el HttpResponse final con el body real.
     */
    suspend fun downloadModel(
        url: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val destDir  = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
        val destFile = File(destDir, MODEL_FILE)
        val tmpFile  = File(destDir, "$MODEL_FILE.tmp")
        var connection: HttpURLConnection? = null
        try {
            connection = openDownloadConnection(url)

            if (connection.responseCode !in 200..299) {
                Log.e(TAG, "downloadModel HTTP error: ${connection.responseCode} for $url")
                return@withContext false
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            Log.d(TAG, "downloadModel starting: totalBytes=$totalBytes url=$url")

            var downloaded = 0L
            connection.inputStream.use { input ->
                tmpFile.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) onProgress(downloaded.toFloat() / totalBytes)
                    }
                }
            }

            Log.d(TAG, "downloadModel finished: downloaded=$downloaded tmpSize=${tmpFile.length()}")

            val validSize = tmpFile.exists() && tmpFile.length() > MIN_VALID_FILE_BYTES
            val complete = totalBytes <= 0 || tmpFile.length() >= totalBytes
            if (validSize && complete) {
                if (destFile.exists()) destFile.delete()
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                onProgress(1f)
                true
            } else {
                Log.e(TAG, "downloadModel: tmpFile too small (${tmpFile.length()} bytes), aborting")
                tmpFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadModel exception", e)
            tmpFile.delete()
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun openDownloadConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FenixIA-Android")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (!location.isNullOrBlank()) {
                    currentUrl = URL(URL(currentUrl), location).toString()
                    return@repeat
                }
            }
            return connection
        }
        error("Demasiadas redirecciones al descargar el modelo")
    }

    fun release() {
        activeListener = null
        isGenerating.set(false)
        closeInferenceSafely()
        inference = null
        _isReady.value = false
    }

    private fun closeInferenceSafely() {
        try {
            inference?.close()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "close skipped while inference was still processing", e)
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
    }

    private fun formatPrompt(prompt: String): String =
        prompt.trim()

    private fun cleanModelOutput(text: String): String =
        text.replace("<|im_start|>assistant", "")
            .replace("<|im_start|>user", "")
            .replace(CHAT_END_TOKEN, "")

}

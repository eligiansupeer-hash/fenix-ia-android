package com.fenix.ia.local

import android.app.ActivityManager
import android.content.Context
import android.util.Log
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

@Singleton
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("download") private val downloadClient: HttpClient
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "LocalLlmEngine"
        const val MODEL_DIR  = "fenix_models"
        const val MODEL_FILE = "gemma_2b_q4.bin"
        const val MIN_RAM_MB = 3_500L
        private const val MAX_TOKENS  = 1024
        private const val TOP_K       = 40
        private const val TEMPERATURE = 0.8f
        private const val MIN_VALID_FILE_BYTES = 1_000_000L
        private const val BUFFER_SIZE = 64 * 1024  // 64 KB chunks
    }

    private var inference: LlmInference? = null
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var activeListener: ((String?, Boolean) -> Unit)? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

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
                .setTopK(TOP_K)
                .setTemperature(TEMPERATURE)
                .setResultListener { partial, done -> activeListener?.invoke(partial, done) }
                .build()
            inference = LlmInference.createFromOptions(context, opts)
            _isReady.value = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "initializeModel failed", e)
            _isReady.value = false
            false
        }
    }

    fun generate(prompt: String): Flow<String> = callbackFlow {
        val model = inference
        if (model == null) {
            trySend("[Error: modelo local no inicializado]")
            close()
            return@callbackFlow
        }
        activeListener = { partial, done ->
            partial?.let { trySend(it) }
            if (done) close()
        }
        model.generateResponseAsync(prompt)
        awaitClose { activeListener = null }
    }.flowOn(Dispatchers.Default)

    /**
     * FIX: usa httpClient.get() en lugar de prepareGet.execute{}.
     *
     * prepareGet.execute{} abre el bloque con el primer response que recibe —
     * si la URL redirige con 302, ese primer response tiene body vacío y el
     * tmpFile queda en 0 bytes aunque OkHttp esté configurado con followRedirects.
     * El motivo: Ktor's prepareGet es un "streaming statement" que captura el
     * response ANTES de que OkHttp complete la cadena de redirects en el engine.
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
        try {
            val response: HttpResponse = downloadClient.get(url)

            if (!response.status.isSuccess()) {
                Log.e(TAG, "downloadModel HTTP error: ${response.status.value} for $url")
                return@withContext false
            }

            val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLong() ?: -1L
            Log.d(TAG, "downloadModel starting: totalBytes=$totalBytes url=$url")

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

            Log.d(TAG, "downloadModel finished: downloaded=$downloaded tmpSize=${tmpFile.length()}")

            if (tmpFile.exists() && tmpFile.length() > MIN_VALID_FILE_BYTES) {
                tmpFile.renameTo(destFile)
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
        }
    }

    fun release() {
        activeListener = null
        inference?.close()
        inference = null
        _isReady.value = false
    }
}

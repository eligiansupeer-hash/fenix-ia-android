package com.fenix.ia.updater

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("api")      private val apiClient: HttpClient,
    @Named("download") private val downloadClient: HttpClient
) {
    companion object {
        private const val TAG              = "UpdateChecker"
        private const val R2_BASE_URL      = "https://pub-40411760a9364f69b3cbc6c7a7cbd359.r2.dev"
        private const val VERSION_JSON_URL = "$R2_BASE_URL/version.json"
        private const val APK_FILENAME     = "fenix-ia-update.apk"
        private const val TMP_FILENAME     = "fenix-ia-update.apk.tmp"
        private const val MAX_RETRIES      = 3
        private const val MIN_VALID_FILE   = 1_000_000L   // 1 MB mínimo para considerar válido
        private const val VERSION_JSON_MAX = 8_192L       // 8 KB — version.json nunca debe ser mayor
        private const val CHUNK_SIZE       = 32 * 1024    // 32 KB — conservador para 2 GB RAM
        // Sin límite superior de tamaño: soporta APKs grandes y modelos IA desde R2
    }

    private val localVersionCode: Int by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            else
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) { 0 }
    }

    private val localVersionName: String by lazy {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0" }
        catch (e: Exception) { "0.0.0" }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ──────────────────────────────────────────────────────────────────────
    // CHECK
    // ──────────────────────────────────────────────────────────────────────

    suspend fun checkForUpdate(): UpdateResult {
        return try {
            val response = apiClient.get(VERSION_JSON_URL) {
                header("User-Agent", "FenixIA-Android/$localVersionName")
                header("Cache-Control", "no-cache")
            }
            when (response.status.value) {
                200  -> parseVersionJson(response)
                404  -> UpdateResult.NoReleases
                else -> UpdateResult.Error("R2 respondió ${response.status.value}")
            }
        } catch (e: Exception) {
            UpdateResult.Error("Error de red: ${e.message}")
        }
    }

    /**
     * Lee version.json acotado a VERSION_JSON_MAX bytes.
     * Usa channel para no materializar el body completo en heap.
     */
    private suspend fun parseVersionJson(response: HttpResponse): UpdateResult {
        val channel = response.bodyAsChannel()
        val builder = StringBuilder()
        val buffer  = ByteArray(1024)
        var totalRead = 0L

        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer)
            if (read <= 0) break
            totalRead += read
            if (totalRead > VERSION_JSON_MAX)
                return UpdateResult.Error("version.json demasiado grande (>${VERSION_JSON_MAX}B)")
            builder.append(String(buffer, 0, read, Charsets.UTF_8))
        }

        val meta = try {
            json.decodeFromString<R2VersionMeta>(builder.toString())
        } catch (e: Exception) {
            return UpdateResult.Error("version.json inválido: ${e.message}")
        }

        if (meta.versionCode <= localVersionCode)
            return UpdateResult.UpToDate(localVersionCode, meta.versionCode)
        if (meta.apkUrl.isBlank())
            return UpdateResult.Error("version.json sin apkUrl")

        return UpdateResult.UpdateAvailable(
            currentVersion = localVersionCode,
            newVersion     = meta.versionCode,
            releaseNotes   = meta.notes.take(500),
            apkUrl         = meta.apkUrl,
            apkSizeBytes   = 0L
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // DOWNLOAD — streaming puro, sin límite de tamaño, sin RAM acumulada
    // Funciona para APKs, modelos IA y cualquier asset en R2.
    // ──────────────────────────────────────────────────────────────────────

    suspend fun downloadAndInstall(
        apkUrl: String,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {

        // Hint al GC antes de abrir el canal — importante en 2 GB RAM
        System.gc()

        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        outputDir.mkdirs()
        val tmpFile = File(outputDir, TMP_FILENAME)
        val apkFile = File(outputDir, APK_FILENAME)
        var lastError = "Error desconocido"

        repeat(MAX_RETRIES) { attempt ->
            val resumeFrom = if (tmpFile.exists()) tmpFile.length() else 0L

            try {
                val response = downloadClient.get(apkUrl) {
                    header("User-Agent", "FenixIA-Android/$localVersionName")
                    if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
                }
                val status = response.status.value

                when {
                    status == 416 -> {
                        // Servidor indica que ya tenemos todos los bytes
                        tmpFile.renameTo(apkFile)
                        onProgress(100)
                        return@withContext launchInstaller(apkFile)
                    }
                    status != 200 && status != 206 -> {
                        lastError = "HTTP $status"
                        return@repeat
                    }
                }

                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLong() ?: -1L
                val totalBytes    = if (resumeFrom > 0 && contentLength > 0)
                    resumeFrom + contentLength else contentLength

                // Streaming real: ByteReadChannel → BufferedOutputStream → disco.
                // chunk es el único buffer en heap; el archivo nunca se acumula en RAM.
                val channel    = response.bodyAsChannel()
                val chunk      = ByteArray(CHUNK_SIZE)
                var downloaded = resumeFrom

                BufferedOutputStream(
                    FileOutputStream(tmpFile, /* append = */ resumeFrom > 0),
                    CHUNK_SIZE
                ).use { out ->
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(chunk)
                        if (read <= 0) break
                        out.write(chunk, 0, read)
                        downloaded += read
                        if (totalBytes > 0)
                            onProgress(
                                ((downloaded.toFloat() / totalBytes) * 100)
                                    .toInt().coerceIn(0, 99)
                            )
                    }
                    out.flush()
                }

                if (tmpFile.length() < MIN_VALID_FILE) {
                    lastError = "Archivo demasiado pequeño (${tmpFile.length()} bytes)"
                    return@repeat
                }

                tmpFile.renameTo(apkFile)
                onProgress(100)
                return@withContext launchInstaller(apkFile)

            } catch (e: Exception) {
                Log.e(TAG, "Intento ${attempt + 1} fallido: ${e.message}")
                lastError = e.message ?: "Excepción desconocida"
                if (attempt < MAX_RETRIES - 1)
                    kotlinx.coroutines.delay(2_000L * (attempt + 1))
            }
        }

        DownloadResult.Error("Descarga fallida tras $MAX_RETRIES intentos: $lastError")
    }

    // ──────────────────────────────────────────────────────────────────────
    // INSTALLER
    // ──────────────────────────────────────────────────────────────────────

    private fun launchInstaller(apkFile: File): DownloadResult {
        return try {
            if (!apkFile.exists()) return DownloadResult.Error("APK no encontrado")
            val apkUri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider", apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            DownloadResult.Success
        } catch (e: Exception) {
            DownloadResult.Error("Error instalador: ${e.message}")
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Data classes / sealed classes
// ──────────────────────────────────────────────────────────────────────────

@Serializable
private data class R2VersionMeta(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String = "",
    val sha: String   = ""
)

sealed class UpdateResult {
    data class UpdateAvailable(
        val currentVersion: Int,
        val newVersion: Int,
        val releaseNotes: String,
        val apkUrl: String,
        val apkSizeBytes: Long
    ) : UpdateResult()
    data class UpToDate(val currentVersion: Int, val remoteVersion: Int) : UpdateResult()
    object NoReleases : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

sealed class DownloadResult {
    object Success : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

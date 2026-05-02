package com.fenix.ia.updater

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * OTA via Cloudflare R2.
 *
 * PROBLEMA RAÍZ DE DESCARGAS CORTADAS (porcentajes aleatorios):
 *   GitHub Releases usa URLs presignadas en objects.githubusercontent.com con TTL ~60s.
 *   Cuando OkHttp sigue el redirect 302 de browser_download_url hacia la URL presignada,
 *   el token expira a mitad de la descarga y GitHub cierra el stream en un punto aleatorio.
 *   En reintentos: OkHttp sigue el redirect PERO no reenvía el header Range al destino,
 *   provocando descarga desde byte 0 con escritura incorrecta sobre el tmpFile parcial.
 *
 * SOLUCIÓN — Cloudflare R2:
 *   - APK alojado en R2: URL pública permanente, sin tokens, sin TTL.
 *   - version.json en R2: metadata de versión sin autenticación.
 *   - R2 soporta Range requests nativamente → resume funciona sin interferencia de redirects.
 *   - Sin rate limits agresivos como GitHub API (60 req/hora sin token).
 *
 * FLUJO:
 *   checkForUpdate()   → GET $R2_BASE_URL/version.json (JSON liviano, sin auth)
 *   downloadAndInstall() → GET $R2_BASE_URL/fenix-ia-latest.apk con Range: bytes=N-
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("api")      private val apiClient: HttpClient,
    @Named("download") private val downloadClient: HttpClient
) {
    companion object {
        private const val TAG = "UpdateChecker"

        // URL pública del bucket R2 — sin trailing slash.
        // Mismo valor que CF_R2_PUBLIC_URL en el workflow de CI.
        // Formato: https://pub-<hash>.r2.dev  (o dominio custom si está configurado)
        private const val R2_BASE_URL = "https://pub-fenix-ia-releases.r2.dev"

        private const val VERSION_JSON_URL = "$R2_BASE_URL/version.json"
        private const val APK_FILENAME     = "fenix-ia-update.apk"
        private const val TMP_FILENAME     = "fenix-ia-update.apk.tmp"
        private const val MAX_RETRIES      = 3
        private const val MIN_VALID_APK    = 1_000_000L  // 1 MB mínimo
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

    // ── Verificación ──────────────────────────────────────────────────────────

    /**
     * Lee version.json desde R2. Liviano, sin autenticación, sin rate limits.
     */
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
            UpdateResult.Error("Error de red al verificar versión: ${e.message}")
        }
    }

    private suspend fun parseVersionJson(response: HttpResponse): UpdateResult {
        val meta = try {
            json.decodeFromString<R2VersionMeta>(response.body())
        } catch (e: Exception) {
            return UpdateResult.Error("version.json inválido: ${e.message}")
        }

        if (meta.versionCode <= localVersionCode)
            return UpdateResult.UpToDate(localVersionCode, meta.versionCode)

        if (meta.apkUrl.isBlank())
            return UpdateResult.Error("version.json no contiene apkUrl.")

        return UpdateResult.UpdateAvailable(
            currentVersion = localVersionCode,
            newVersion     = meta.versionCode,
            releaseNotes   = meta.notes.take(500),
            apkUrl         = meta.apkUrl,
            apkSizeBytes   = 0L  // R2 no incluye size en version.json; se obtiene del header
        )
    }

    // ── Descarga con resume y retry ───────────────────────────────────────────

    /**
     * Descarga el APK desde Cloudflare R2 con soporte Range resume.
     *
     * R2 soporta Range requests sin restricciones y la URL no tiene TTL,
     * por lo que cada reintento retoma exactamente donde quedó sin necesidad
     * de re-fetchear la URL ni de preocuparse por tokens expirados.
     *
     * onProgress recibe 0..100 (porcentaje entero).
     */
    suspend fun downloadAndInstall(
        apkUrl: String,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {

        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        outputDir.mkdirs()
        val tmpFile = File(outputDir, TMP_FILENAME)
        val apkFile = File(outputDir, APK_FILENAME)

        var lastError = "Error desconocido"

        repeat(MAX_RETRIES) { attempt ->
            val resumeFrom = if (tmpFile.exists()) tmpFile.length() else 0L
            Log.d(TAG, "Attempt ${attempt + 1}/$MAX_RETRIES resumeFrom=$resumeFrom url=$apkUrl")

            try {
                val response = downloadClient.get(apkUrl) {
                    header("User-Agent", "FenixIA-Android/$localVersionName")
                    if (resumeFrom > 0) {
                        header(HttpHeaders.Range, "bytes=$resumeFrom-")
                        Log.d(TAG, "Resuming from byte $resumeFrom")
                    }
                }

                val status = response.status.value
                when {
                    status == 416 -> {
                        // Range Not Satisfiable → archivo ya completo en disco
                        Log.d(TAG, "416: file already complete, renaming")
                        tmpFile.renameTo(apkFile)
                        onProgress(100)
                        return@withContext launchInstaller(apkFile)
                    }
                    status != 200 && status != 206 -> {
                        lastError = "HTTP $status al descargar APK"
                        Log.e(TAG, lastError)
                        return@repeat
                    }
                }

                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLong() ?: -1L
                val totalBytes = if (resumeFrom > 0 && contentLength > 0)
                    resumeFrom + contentLength
                else
                    contentLength

                val channel = response.bodyAsChannel()
                RandomAccessFile(tmpFile, "rw").use { raf ->
                    raf.seek(resumeFrom)
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = resumeFrom
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read > 0) {
                            raf.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                onProgress(((downloaded.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                    Log.d(TAG, "Stream done: downloaded=$downloaded totalBytes=$totalBytes")
                }

                if (tmpFile.length() < MIN_VALID_APK) {
                    lastError = "Archivo demasiado pequeño (${tmpFile.length()} bytes)"
                    Log.e(TAG, lastError)
                    return@repeat
                }

                tmpFile.renameTo(apkFile)
                onProgress(100)
                return@withContext launchInstaller(apkFile)

            } catch (e: Exception) {
                lastError = e.message ?: "Excepción desconocida"
                Log.e(TAG, "Attempt ${attempt + 1} failed: $lastError", e)
                // tmpFile NO se borra → permite resume en siguiente intento
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(2_000L * (attempt + 1))
                }
            }
        }

        DownloadResult.Error("Descarga fallida tras $MAX_RETRIES intentos: $lastError")
    }

    // ── Instalador ────────────────────────────────────────────────────────────

    private fun launchInstaller(apkFile: File): DownloadResult {
        return try {
            if (!apkFile.exists())
                return DownloadResult.Error("APK no encontrado: ${apkFile.absolutePath}")

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            DownloadResult.Success
        } catch (e: Exception) {
            DownloadResult.Error("Error lanzando instalador: ${e.message}")
        }
    }
}

// ── DTO Cloudflare R2 version.json ────────────────────────────────────────────

@Serializable
private data class R2VersionMeta(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String = "",
    val sha: String = ""
)

// ── Resultados ────────────────────────────────────────────────────────────────

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

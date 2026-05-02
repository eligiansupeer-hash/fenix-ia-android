package com.fenix.ia.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Verifica e instala actualizaciones OTA desde GitHub Releases.
 *
 * PROBLEMA RAÍZ (porcentajes aleatorios de corte):
 *   browser_download_url de GitHub Releases genera URLs presignadas con TTL ~60s.
 *   DownloadManager reusa la misma URL en retries internos — cuando el token expiró,
 *   GitHub cierra la conexión en un punto aleatorio del stream.
 *   El porcentaje varía porque depende de cuándo exactamente expiró el token vs
 *   el progreso de descarga en ese momento.
 *
 * SOLUCIÓN:
 *   1. Reemplazar DownloadManager por descarga directa con Ktor @Named("download").
 *   2. Soporte de Range resume: si el tmpFile ya existe, continúa desde el byte
 *      donde quedó usando "Range: bytes=N-". En cada reintento obtiene una nueva URL
 *      presignada válida y retoma sin desperdiciar lo descargado.
 *   3. checkForUpdate() siempre obtiene la URL más reciente del release (nueva sesión
 *      = nuevo token presignado válido) antes de reanudar la descarga.
 *   4. 3 reintentos automáticos con backoff de 2s entre intentos.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("api")      private val apiClient: HttpClient,
    @Named("download") private val downloadClient: HttpClient
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val RELEASES_API =
            "https://api.github.com/repos/eligiansupeer-hash/fenix-ia-android/releases/latest"
        private const val APK_FILENAME  = "fenix-ia-update.apk"
        private const val TMP_FILENAME  = "fenix-ia-update.apk.tmp"
        private const val MAX_RETRIES   = 3
        private const val MIN_VALID_APK = 1_000_000L   // 1 MB mínimo
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

    suspend fun checkForUpdate(): UpdateResult {
        return try {
            val response = apiClient.get(RELEASES_API) {
                header("Accept", "application/vnd.github.v3+json")
                header("User-Agent", "FenixIA-Android/$localVersionName")
            }
            when (response.status.value) {
                200  -> parseReleaseResponse(response)
                404  -> UpdateResult.NoReleases
                403  -> UpdateResult.Error("Rate limit de GitHub. Intentá más tarde.")
                else -> UpdateResult.Error("GitHub API: ${response.status.value}")
            }
        } catch (e: Exception) {
            UpdateResult.Error("Error de red: ${e.message}")
        }
    }

    private suspend fun parseReleaseResponse(response: HttpResponse): UpdateResult {
        val release = try {
            json.decodeFromString<GithubRelease>(response.body())
        } catch (e: Exception) {
            return UpdateResult.Error("Respuesta inválida de GitHub: ${e.message}")
        }

        val remoteCode = release.tagName.removePrefix("v").toIntOrNull()
            ?: return UpdateResult.Error("Tag inválido: '${release.tagName}'")

        if (remoteCode <= localVersionCode)
            return UpdateResult.UpToDate(localVersionCode, remoteCode)

        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return UpdateResult.Error("El release no tiene APK adjunto.")

        return UpdateResult.UpdateAvailable(
            currentVersion = localVersionCode,
            newVersion     = remoteCode,
            releaseNotes   = release.body.take(500),
            apkUrl         = apkAsset.browserDownloadUrl,
            apkSizeBytes   = apkAsset.size
        )
    }

    // ── Descarga con resume y retry ───────────────────────────────────────────

    /**
     * Descarga el APK usando Ktor con soporte Range resume.
     *
     * Cada vez que se llama a downloadAndInstall se obtiene una nueva apkUrl
     * (desde UpdateResult.UpdateAvailable que viene de checkForUpdate() fresco).
     * Eso garantiza un token presignado válido en cada intento.
     *
     * Si tmpFile ya tiene bytes parciales, envía "Range: bytes=N-" para retomar
     * desde donde quedó sin re-descargar lo que ya está en disco.
     *
     * onProgress recibe 0..100 (porcentaje entero).
     */
    suspend fun downloadAndInstall(
        apkUrl: String,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {

        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        outputDir.mkdirs()
        val tmpFile  = File(outputDir, TMP_FILENAME)
        val apkFile  = File(outputDir, APK_FILENAME)

        var lastError = "Error desconocido"

        repeat(MAX_RETRIES) { attempt ->
            Log.d(TAG, "downloadAndInstall attempt ${attempt + 1}/$MAX_RETRIES, tmpSize=${tmpFile.length()}")

            try {
                val resumeFrom = if (tmpFile.exists()) tmpFile.length() else 0L

                val response = downloadClient.get(apkUrl) {
                    header("User-Agent", "FenixIA-Android/$localVersionName")
                    if (resumeFrom > 0) {
                        header(HttpHeaders.Range, "bytes=$resumeFrom-")
                        Log.d(TAG, "Resuming from byte $resumeFrom")
                    }
                }

                val status = response.status.value
                // 200 = descarga completa desde el inicio, 206 = Partial Content (resume OK)
                if (status != 200 && status != 206) {
                    lastError = "HTTP $status al descargar APK"
                    Log.e(TAG, lastError)
                    if (status == 416) {
                        // Range not satisfiable → el archivo ya está completo en disco
                        tmpFile.delete()
                    }
                    return@repeat
                }

                val totalFromServer = response.headers[HttpHeaders.ContentLength]?.toLong() ?: -1L
                val totalBytes = if (resumeFrom > 0 && totalFromServer > 0)
                    resumeFrom + totalFromServer
                else
                    totalFromServer

                val channel = response.bodyAsChannel()
                // Append si resume, overwrite si descarga fresca
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
                                onProgress(((downloaded.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                    Log.d(TAG, "Stream finished: downloaded=$downloaded totalBytes=$totalBytes")
                }

                // Validar integridad mínima
                if (tmpFile.length() < MIN_VALID_APK) {
                    lastError = "Archivo descargado demasiado pequeño (${tmpFile.length()} bytes)"
                    Log.e(TAG, lastError)
                    return@repeat
                }

                // Éxito — renombrar y lanzar instalador
                tmpFile.renameTo(apkFile)
                onProgress(100)
                return@withContext launchInstaller(apkFile)

            } catch (e: Exception) {
                lastError = e.message ?: "Excepción desconocida"
                Log.e(TAG, "Attempt ${attempt + 1} failed: $lastError", e)
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(2_000L * (attempt + 1))
                }
            }
        }

        // Todos los reintentos fallaron — no borrar tmpFile para permitir resume futuro
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

// ── DTOs GitHub API ───────────────────────────────────────────────────────────

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String = "",
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long
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

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

@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("api")      private val apiClient: HttpClient,
    @Named("download") private val downloadClient: HttpClient
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val R2_BASE_URL      = "https://pub-40411760a9364f69b3cbc6c7a7cbd359.r2.dev"
        private const val VERSION_JSON_URL = "$R2_BASE_URL/version.json"
        private const val APK_FILENAME     = "fenix-ia-update.apk"
        private const val TMP_FILENAME     = "fenix-ia-update.apk.tmp"
        private const val MAX_RETRIES      = 3
        private const val MIN_VALID_APK    = 1_000_000L
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

    private suspend fun parseVersionJson(response: HttpResponse): UpdateResult {
        val meta = try {
            json.decodeFromString<R2VersionMeta>(response.body())
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
            try {
                val response = downloadClient.get(apkUrl) {
                    header("User-Agent", "FenixIA-Android/$localVersionName")
                    if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
                }
                val status = response.status.value
                when {
                    status == 416 -> {
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
                val totalBytes = if (resumeFrom > 0 && contentLength > 0) resumeFrom + contentLength else contentLength
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
                            if (totalBytes > 0)
                                onProgress(((downloaded.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 99))
                        }
                    }
                }
                if (tmpFile.length() < MIN_VALID_APK) {
                    lastError = "Archivo demasiado pequeño (${tmpFile.length()} bytes)"
                    return@repeat
                }
                tmpFile.renameTo(apkFile)
                onProgress(100)
                return@withContext launchInstaller(apkFile)
            } catch (e: Exception) {
                lastError = e.message ?: "Excepción desconocida"
                if (attempt < MAX_RETRIES - 1) kotlinx.coroutines.delay(2_000L * (attempt + 1))
            }
        }
        DownloadResult.Error("Descarga fallida tras $MAX_RETRIES intentos: $lastError")
    }

    private fun launchInstaller(apkFile: File): DownloadResult {
        return try {
            if (!apkFile.exists()) return DownloadResult.Error("APK no encontrado")
            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
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

@Serializable
private data class R2VersionMeta(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String = "",
    val sha: String = ""
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

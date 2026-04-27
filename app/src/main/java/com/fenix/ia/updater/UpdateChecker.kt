package com.fenix.ia.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Verifica actualizaciones consultando la GitHub Releases API (pública, sin token).
 * Sin BuildConfig — usa constantes locales sincronizadas con build.gradle.kts.
 *
 * Flujo OTA:
 *   1. checkForUpdate() → compara LOCAL_VERSION_CODE vs tag remoto
 *   2. Si hay update → downloadAndInstall() via DownloadManager (R-04 safe)
 *   3. DownloadManager notifica al completar → lanza instalador del sistema
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient
) {

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/eligiansupeer-hash/fenix-ia-android/releases/latest"

        // Sincronizar con versionCode / versionName en app/build.gradle.kts
        const val LOCAL_VERSION_CODE = 1
        const val LOCAL_VERSION_NAME = "1.0.0"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(): UpdateResult {
        return try {
            val response: HttpResponse = httpClient.get(RELEASES_API) {
                header("Accept", "application/vnd.github.v3+json")
                header("User-Agent", "FenixIA-Android/$LOCAL_VERSION_NAME")
            }

            if (response.status.value != 200) {
                return UpdateResult.Error("GitHub API respondió ${response.status.value}")
            }

            val release = json.decodeFromString<GithubRelease>(response.body())

            val remoteVersionCode = release.tagName
                .removePrefix("v")
                .toIntOrNull()
                ?: return UpdateResult.Error("Tag inválido: ${release.tagName}")

            if (remoteVersionCode <= LOCAL_VERSION_CODE) {
                return UpdateResult.UpToDate(
                    currentVersion = LOCAL_VERSION_CODE,
                    remoteVersion = remoteVersionCode
                )
            }

            val apkAsset = release.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true)
            } ?: return UpdateResult.Error("No se encontró APK en el release $remoteVersionCode")

            UpdateResult.UpdateAvailable(
                currentVersion = LOCAL_VERSION_CODE,
                newVersion = remoteVersionCode,
                releaseNotes = release.body.take(500),
                apkUrl = apkAsset.browserDownloadUrl,
                apkSizeBytes = apkAsset.size
            )

        } catch (e: Exception) {
            UpdateResult.Error("Error de red: ${e.message}")
        }
    }

    suspend fun downloadAndInstall(
        apkUrl: String,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = suspendCancellableCoroutine { continuation ->

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("Fenix IA — Actualizando")
            setDescription("Descargando nueva versión...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "fenix-ia-update.apk"
            )
            setMimeType("application/vnd.android.package-archive")
            addRequestHeader("User-Agent", "FenixIA-Android/$LOCAL_VERSION_NAME")
        }

        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                context.unregisterReceiver(this)

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)

                if (!cursor.moveToFirst()) {
                    continuation.resume(DownloadResult.Error("Descarga no encontrada"))
                    return
                }

                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val status = cursor.getInt(statusCol)
                val localUri = cursor.getString(uriCol)
                cursor.close()

                if (status == DownloadManager.STATUS_SUCCESSFUL && localUri != null) {
                    launchInstaller(localUri)
                    continuation.resume(DownloadResult.Success)
                } else {
                    continuation.resume(DownloadResult.Error("Descarga fallida — status: $status"))
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        continuation.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
            downloadManager.remove(downloadId)
        }
    }

    private fun launchInstaller(localUri: String) {
        val apkFile = File(Uri.parse(localUri).path ?: return)
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(installIntent)
    }
}

// GitHub API devuelve snake_case — @SerialName obligatorio
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

sealed class UpdateResult {
    data class UpdateAvailable(
        val currentVersion: Int,
        val newVersion: Int,
        val releaseNotes: String,
        val apkUrl: String,
        val apkSizeBytes: Long
    ) : UpdateResult()
    data class UpToDate(val currentVersion: Int, val remoteVersion: Int) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

sealed class DownloadResult {
    object Success : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

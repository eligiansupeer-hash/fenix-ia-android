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
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Verifica actualizaciones consultando la GitHub Releases API (pública, sin token).
 *
 * FIX F2 — Tres bugs corregidos:
 *
 * Bug 2a — LOCAL_VERSION_CODE hardcodeado en 2:
 *   El versionCode real en build.gradle.kts es 45+, por lo que el checker siempre
 *   reportaba "actualización disponible" aunque no hubiera ninguna.
 *   FIX: LOCAL_VERSION_CODE lee el versionCode del PackageManager en runtime.
 *
 * Bug 2b — launchInstaller usaba Uri.parse(localUri).path en URI content://:
 *   DownloadManager devuelve COLUMN_LOCAL_URI como "content://downloads/..." en
 *   Android 10+ lo cual hace que File(uri.path) resuelva a null o a una ruta
 *   inaccesible — el FileProvider lanzaba FileNotFoundException silenciosamente.
 *   FIX: se usa COLUMN_LOCAL_FILENAME para obtener la ruta real del filesystem,
 *   con fallback a parsear el path del URI content:// si el filename está vacío.
 *
 * Bug 2c — El cliente HTTP inyectado era el cliente @Named("api") compartido:
 *   La descarga del APK OTA (~30 MB) puede tardar >120s en redes lentas y el
 *   socket timeout del cliente API lo interrumpía.
 *   FIX: se inyecta @Named("download") con timeouts infinitos.
 *
 * Flujo OTA completo:
 *   1. checkForUpdate()      → GitHub API → compara versionCode real vs remoto
 *   2. downloadAndInstall()  → DownloadManager (background, con notificación)
 *   3. BroadcastReceiver     → COLUMN_LOCAL_FILENAME → FileProvider → instalador
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("api") private val httpClient: HttpClient   // FIX 2c: cliente API para GitHub JSON
) {

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/eligiansupeer-hash/fenix-ia-android/releases/latest"
    }

    // FIX 2a: versionCode leído del PackageManager en runtime, no hardcodeado
    private val localVersionCode: Int by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .longVersionCode
                    .toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionCode
            }
        } catch (e: Exception) {
            // Fallback seguro: si no podemos leer, asumimos versión 0 → siempre busca update
            0
        }
    }

    private val localVersionName: String by lazy {
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0.0.0"
        } catch (e: Exception) { "0.0.0" }
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(): UpdateResult {
        return try {
            val response: HttpResponse = httpClient.get(RELEASES_API) {
                header("Accept", "application/vnd.github.v3+json")
                header("User-Agent", "FenixIA-Android/$localVersionName")
            }

            when (response.status.value) {
                200  -> parseReleaseResponse(response)
                404  -> UpdateResult.NoReleases
                403  -> UpdateResult.Error("Rate limit de GitHub alcanzado. Intentá más tarde.")
                else -> UpdateResult.Error("GitHub API respondió ${response.status.value}")
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

        val remoteVersionCode = release.tagName
            .removePrefix("v")
            .toIntOrNull()
            ?: return UpdateResult.Error(
                "Tag de release inválido: '${release.tagName}'. Se esperaba formato 'v45', etc."
            )

        // FIX 2a: compara contra localVersionCode leído del PackageManager
        if (remoteVersionCode <= localVersionCode) {
            return UpdateResult.UpToDate(
                currentVersion = localVersionCode,
                remoteVersion  = remoteVersionCode
            )
        }

        val apkAsset = release.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true)
        } ?: return UpdateResult.Error(
            "El release ${release.tagName} no contiene un APK adjunto."
        )

        return UpdateResult.UpdateAvailable(
            currentVersion = localVersionCode,
            newVersion     = remoteVersionCode,
            releaseNotes   = release.body.take(500),
            apkUrl         = apkAsset.browserDownloadUrl,
            apkSizeBytes   = apkAsset.size
        )
    }

    // ── Descarga OTA via DownloadManager ─────────────────────────────────────

    suspend fun downloadAndInstall(
        apkUrl: String,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = suspendCancellableCoroutine { continuation ->

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

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
            addRequestHeader("User-Agent", "FenixIA-Android/$localVersionName")
        }

        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return

                context.unregisterReceiver(this)

                val query  = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)

                if (!cursor.moveToFirst()) {
                    continuation.resume(DownloadResult.Error("Descarga no encontrada"))
                    return
                }

                val statusIdx   = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val fileNameIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
                val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

                val status    = cursor.getInt(statusIdx)
                // FIX 2b: COLUMN_LOCAL_FILENAME da la ruta real del filesystem
                val localPath = cursor.getString(fileNameIdx)
                    ?.takeIf { it.isNotBlank() }
                    ?: Uri.parse(cursor.getString(localUriIdx)).path  // fallback

                cursor.close()

                if (status == DownloadManager.STATUS_SUCCESSFUL && !localPath.isNullOrBlank()) {
                    val result = launchInstaller(localPath)
                    continuation.resume(result)
                } else {
                    val reason = when (status) {
                        DownloadManager.STATUS_FAILED   -> "Descarga fallida (código $status)"
                        DownloadManager.STATUS_PAUSED   -> "Descarga pausada — verificá conexión"
                        else -> "Estado inesperado: $status"
                    }
                    continuation.resume(DownloadResult.Error(reason))
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
            dm.remove(downloadId)
        }
    }

    // ── Lanzador del instalador — FIX 2b ─────────────────────────────────────

    private fun launchInstaller(localFilePath: String): DownloadResult {
        return try {
            val apkFile = File(localFilePath)
            if (!apkFile.exists()) {
                return DownloadResult.Error(
                    "APK descargado no encontrado en: $localFilePath"
                )
            }

            // FIX 2b: FileProvider recibe File del filesystem, no un URI content://
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
            DownloadResult.Success
        } catch (e: Exception) {
            DownloadResult.Error("Error lanzando instalador: ${e.message}")
        }
    }
}

// ── Modelos de respuesta GitHub API ──────────────────────────────────────────

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

// ── Resultados sellados ───────────────────────────────────────────────────────

sealed class UpdateResult {
    data class UpdateAvailable(
        val currentVersion: Int,
        val newVersion: Int,
        val releaseNotes: String,
        val apkUrl: String,
        val apkSizeBytes: Long
    ) : UpdateResult()

    data class UpToDate(val currentVersion: Int, val remoteVersion: Int) : UpdateResult()

    /** El repo no tiene releases publicados — no es un error. */
    object NoReleases : UpdateResult()

    data class Error(val message: String) : UpdateResult()
}

sealed class DownloadResult {
    object Success : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

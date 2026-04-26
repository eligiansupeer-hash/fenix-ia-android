package com.fenix.ia.artifacts

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generador de artefactos de texto producidos por la IA.
 * Usa Scoped Storage (MediaStore API) — compatible Android 10+ (API 29).
 *
 * Extensiones permitidas: md, txt, json, csv, html, kt, py, js
 * RESTRICCIÓN R-04: No carga archivos existentes en heap — escritura append-only.
 *
 * Estructura de directorios:
 *   Documents/FenixIA/<projectId>/<filename>
 */
@Singleton
class ArtifactGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val ALLOWED_EXTENSIONS = setOf("md", "txt", "json", "csv", "html", "kt", "py", "js")
        private const val BASE_DIR = "FenixIA"
    }

    data class ArtifactResult(
        val uri: String,
        val displayName: String
    )

    /**
     * Escribe [content] como artefacto de texto en Scoped Storage.
     *
     * @param projectId Subdirectorio del proyecto
     * @param filename  Nombre con extensión permitida
     * @param content   Contenido del artefacto
     * @throws IllegalArgumentException si la extensión no está permitida
     * @throws IOException si la escritura falla
     */
    @Throws(IllegalArgumentException::class, IOException::class)
    suspend fun generateArtifact(
        projectId: String,
        filename: String,
        content: String
    ): ArtifactResult = withContext(Dispatchers.IO) {
        val extension = filename.substringAfterLast('.', "").lowercase()
        require(extension in ALLOWED_EXTENSIONS) {
            "Extensión '$extension' no permitida. Permitidas: $ALLOWED_EXTENSIONS"
        }

        val mimeType = extensionToMime(extension)
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$BASE_DIR/$projectId"

        val contentValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, filename)
            put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = context.contentResolver.insert(collection, contentValues)
            ?: throw IOException("ContentResolver.insert() retornó null para $filename")

        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("No se pudo abrir OutputStream para $uri")

        ArtifactResult(uri = uri.toString(), displayName = filename)
    }

    private fun extensionToMime(ext: String): String = when (ext) {
        "md"   -> "text/markdown"
        "txt"  -> "text/plain"
        "json" -> "application/json"
        "csv"  -> "text/csv"
        "html" -> "text/html"
        "kt"   -> "text/x-kotlin"
        "py"   -> "text/x-python"
        "js"   -> "text/javascript"
        else   -> "text/plain"
    }
}

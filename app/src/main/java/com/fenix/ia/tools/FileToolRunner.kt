package com.fenix.ia.tools

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

object FileToolRunner {
    fun readFile(context: Context, args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'path' requerido")
        val maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull ?: 10_000

        val file = resolveAppFile(context, path)
            ?: return ToolResult.Error("Ruta fuera del espacio privado de la app")
        if (!file.exists()) return ToolResult.Error("Archivo no encontrado: $path")

        val content = file.bufferedReader().use { reader ->
            val buf = CharArray(maxChars)
            val read = reader.read(buf)
            if (read > 0) String(buf, 0, read) else ""
        }

        return ToolResult.Success(buildJsonObject {
            put("content", content)
            put("truncated", file.length() > maxChars)
        }.toString())
    }

    fun createFile(context: Context, args: JsonObject): ToolResult {
        val fileName = (args["fileName"] ?: args["file_name"])?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'fileName' requerido")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'content' requerido")
        val projectId = (args["projectId"] ?: args["project_id"])?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'projectId' requerido")

        val safeProjectId = projectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val safeFileName = fileName.replace('\\', '/').substringAfterLast('/').take(120)
        if (safeFileName.isBlank() || safeFileName == "." || safeFileName == "..") {
            return ToolResult.Error("Nombre de archivo invalido")
        }

        val dir = File(context.filesDir, "projects/$safeProjectId").also { it.mkdirs() }
        val file = resolveAppFile(context, File(dir, safeFileName).absolutePath)
            ?: return ToolResult.Error("Ruta fuera del espacio privado de la app")
        file.writeText(content)

        return ToolResult.Success(buildJsonObject {
            put("path", file.absolutePath)
            put("success", true)
            put("preview", content.take(400))
        }.toString())
    }

    fun editFile(context: Context, args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'path' requerido")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Argumento 'content' requerido")
        val append = args["append"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        val file = resolveAppFile(context, path)
            ?: return ToolResult.Error("Ruta fuera del espacio privado de la app")
        if (!file.exists()) return ToolResult.Error("Archivo no encontrado: $path")

        if (append) file.appendText(content) else file.writeText(content)

        return ToolResult.Success(buildJsonObject {
            put("path", file.absolutePath)
            put("success", true)
            put("preview", content.take(400))
        }.toString())
    }

    fun resolveAppFile(context: Context, path: String): File? {
        val candidate = File(path).canonicalFile
        val filesRoot = context.filesDir.canonicalFile
        val cacheRoot = context.cacheDir.canonicalFile
        val allowed = candidate.path == filesRoot.path ||
            candidate.path.startsWith(filesRoot.path + File.separator) ||
            candidate.path == cacheRoot.path ||
            candidate.path.startsWith(cacheRoot.path + File.separator)
        return if (allowed) candidate else null
    }
}

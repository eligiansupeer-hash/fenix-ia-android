package com.fenix.ia.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.fenix.ia.audit.AuditLogger
import com.fenix.ia.data.local.db.dao.MessageAttachmentDao
import com.fenix.ia.data.local.db.entities.MessageAttachmentEntity
import com.fenix.ia.data.local.objectbox.RagProjectId
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.repository.DocumentRepository
import com.fenix.ia.ingestion.DocumentIngestionWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PreparedAttachment(
    val document: DocumentNode,
    val sourceUri: String,
    val privateUri: String,
    val checksum: String
)

@Singleton
class AttachmentService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository,
    private val attachmentDao: MessageAttachmentDao
) {
    suspend fun prepareForMessage(
        chatId: String,
        messageId: String,
        projectId: String,
        rawUris: List<String>
    ): List<PreparedAttachment> = withContext(Dispatchers.IO) {
        if (rawUris.isEmpty()) return@withContext emptyList()
        val normalizedProjectId = projectId.ifBlank { "general" }

        val prepared = rawUris.distinct().mapNotNull { raw ->
            runCatching {
                val source = Uri.parse(raw)
                val (name, mimeType, size) = resolveMetadata(source)
                val documentId = UUID.randomUUID().toString()
                val privateUri = copyToPrivateStorage(source, normalizedProjectId, documentId, name)
                val checksum = sha256(privateUri)

                val document = DocumentNode(
                    id = documentId,
                    projectId = normalizedProjectId,
                    name = name,
                    uri = privateUri.toString(),
                    mimeType = mimeType,
                    sizeBytes = size,
                    semanticSummary = "Procesando...",
                    isChecked = true,
                    status = "pending",
                    createdAt = System.currentTimeMillis()
                )

                documentRepository.insertDocument(document)
                enqueueIngestion(document)

                PreparedAttachment(
                    document = document,
                    sourceUri = raw,
                    privateUri = privateUri.toString(),
                    checksum = checksum
                )
            }.onFailure { e ->
                AuditLogger.error(
                    "attachment_prepare_failed",
                    e,
                    mapOf("chatId" to chatId, "messageId" to messageId, "uriLength" to raw.length.toString())
                )
            }.getOrNull()
        }

        attachmentDao.deleteAttachmentsByMessage(messageId)
        attachmentDao.insertAttachments(
            prepared.map { item ->
                MessageAttachmentEntity(
                    id = UUID.randomUUID().toString(),
                    messageId = messageId,
                    chatId = chatId,
                    projectId = normalizedProjectId,
                    documentId = item.document.id,
                    mimeType = item.document.mimeType,
                    status = item.document.status,
                    checksum = item.checksum,
                    sourceUri = item.sourceUri,
                    privateUri = item.privateUri,
                    createdAt = item.document.createdAt
                )
            }
        )

        AuditLogger.action(
            "attachments_prepared",
            mapOf(
                "chatId" to chatId,
                "messageId" to messageId,
                "projectId" to normalizedProjectId,
                "count" to prepared.size.toString()
            )
        )
        prepared
    }

    private fun resolveMetadata(uri: Uri): Triple<String, String, Long> {
        if (uri.scheme == "file") {
            val file = File(uri.path.orEmpty())
            val name = file.name.ifBlank { "adjunto_${System.currentTimeMillis()}" }
            return Triple(name, guessMimeType(name), file.length().coerceAtLeast(0L))
        }

        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: guessMimeType(uri.toString())
        var name = "adjunto_${System.currentTimeMillis()}"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        return Triple(name, mimeType, size)
    }

    private fun copyToPrivateStorage(uri: Uri, projectId: String, documentId: String, fileName: String): Uri {
        val safeProjectId = projectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val safeFileName = fileName.replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "documento" }
            .take(120)
        val targetDir = File(context.filesDir, "uploaded_documents/$safeProjectId").also { it.mkdirs() }
        val target = File(targetDir, "${documentId}_$safeFileName")

        val input = if (uri.scheme == "file") {
            FileInputStream(File(uri.path.orEmpty()))
        } else {
            context.contentResolver.openInputStream(uri)
        } ?: error("No se pudo abrir el adjunto")

        input.use { source ->
            target.outputStream().use { output -> source.copyTo(output) }
        }
        return Uri.fromFile(target)
    }

    private fun enqueueIngestion(document: DocumentNode) {
        val request = DocumentIngestionWorker.buildRequest(
            documentId = document.id,
            projectId = RagProjectId.stableLong(document.projectId),
            projectIdString = document.projectId
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ingestion_queue_${document.projectId}",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = if (uri.scheme == "file") {
            FileInputStream(File(uri.path.orEmpty()))
        } else {
            context.contentResolver.openInputStream(uri)
        } ?: return ""

        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun guessMimeType(name: String): String = when {
        name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
        name.endsWith(".docx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        name.endsWith(".txt", ignoreCase = true) -> "text/plain"
        name.endsWith(".md", ignoreCase = true) -> "text/markdown"
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        else -> "application/octet-stream"
    }
}

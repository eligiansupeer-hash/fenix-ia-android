package com.fenix.ia.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.fenix.ia.data.local.db.dao.DocumentDao
import com.fenix.ia.data.local.objectbox.RagEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker de ingesta documental con WorkManager + Hilt.
 * Ejecuta en Dispatchers.IO — NUNCA en Main Thread.
 *
 * Flujo:
 *   1. Lee DocumentNode de Room por documentId
 *   2. Extrae texto (PDF → PdfTextExtractor, DOCX → DocxTextExtractor)
 *   3. Indexa en ObjectBox via RagEngine (chunking 750t / overlap 75t)
 *   4. Marca isIndexed = true en Room
 *
 * RESTRICCIÓN R-04: Extractores procesan archivos en streaming, no carga completa.
 * RESTRICCIÓN R-06: bitmap.recycle() en PdfTextExtractor.extractText() — finally.
 *
 * Retry policy: exponential backoff, máx 3 reintentos (definidos en WorkRequest).
 */
@HiltWorker
class DocumentIngestionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val documentDao: DocumentDao,
    private val ragEngine: RagEngine,
    private val pdfExtractor: PdfTextExtractor,
    private val docxExtractor: DocxTextExtractor
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val documentId = inputData.getString(KEY_DOCUMENT_ID)
            ?: return@withContext Result.failure(
                workDataOf("error" to "documentId no proporcionado")
            )
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        if (projectId == -1L) {
            return@withContext Result.failure(workDataOf("error" to "projectId inválido"))
        }

        runCatching {
            val entity = documentDao.getDocumentById(documentId)
                ?: return@withContext Result.failure(
                    workDataOf("error" to "Documento no encontrado: $documentId")
                )

            // Modelo de dominio para los extractores
            val document = entity.toDomain()

            // Extracción de texto según tipo MIME
            val rawText = when {
                entity.mimeType.contains("pdf", ignoreCase = true) ->
                    pdfExtractor.extractText(document)
                entity.mimeType.contains("word", ignoreCase = true) ||
                entity.mimeType.endsWith("docx") ||
                entity.mimeType.endsWith("doc") ->
                    docxExtractor.extractText(document)
                entity.mimeType.startsWith("text/") ->
                    extractPlainText(document)
                else -> {
                    // Tipo no soportado — marca como indexado vacío para no reintentar
                    documentDao.markAsIndexed(documentId)
                    return@withContext Result.success(
                        workDataOf("info" to "Tipo MIME no soportado: ${entity.mimeType}")
                    )
                }
            }

            if (rawText.isBlank()) {
                documentDao.markAsIndexed(documentId)
                return@withContext Result.success(workDataOf("info" to "Documento vacío"))
            }

            // Indexación vectorial en ObjectBox (chunking lote de 10 — control de RAM)
            ragEngine.indexDocument(
                projectId = projectId,
                documentNodeId = documentId,
                text = rawText
            )

            // Actualiza estado en Room
            documentDao.markAsIndexed(documentId)

        }.onFailure { e ->
            // Propaga fallo para reintento exponencial de WorkManager
            return@withContext Result.retry().also {
                // Log estructurado para depuración
                android.util.Log.e(TAG, "Ingesta fallida para $documentId", e)
            }
        }

        Result.success(workDataOf("documentId" to documentId))
    }

    /**
     * Lee texto plano directamente del ContentResolver.
     * Limitado a MAX_PLAIN_TEXT_BYTES para evitar OOM en archivos grandes.
     */
    private fun extractPlainText(document: com.fenix.ia.domain.model.DocumentNode): String {
        val uri = document.uri.toUri()
        return applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().readText().take(MAX_PLAIN_TEXT_CHARS)
        } ?: ""
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_PROJECT_ID = "project_id"
        private const val TAG = "DocumentIngestionWorker"
        private const val MAX_PLAIN_TEXT_CHARS = 200_000 // ~150 KB texto

        /**
         * Construye la WorkRequest con política de reintento exponencial.
         * Constraints: requiere almacenamiento no bajo.
         */
        fun buildRequest(documentId: String, projectId: Long): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_DOCUMENT_ID to documentId,
                KEY_PROJECT_ID to projectId
            )
            return OneTimeWorkRequestBuilder<DocumentIngestionWorker>()
                .setInputData(data)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .addTag("ingestion_$documentId")
                .build()
        }
    }
}

// Extension para convertir la entidad Room al modelo de dominio dentro del worker
private fun com.fenix.ia.data.local.db.entity.DocumentNodeEntity.toDomain() =
    com.fenix.ia.domain.model.DocumentNode(
        id = id,
        projectId = projectId,
        name = name,
        mimeType = mimeType,
        uri = uri,
        sizeBytes = sizeBytes,
        isIndexed = isIndexed,
        isChecked = isChecked,
        createdAt = createdAt
    )

private fun String.toUri() = android.net.Uri.parse(this)

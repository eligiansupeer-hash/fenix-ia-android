package com.fenix.ia.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.fenix.ia.data.local.db.dao.DocumentDao
import com.fenix.ia.data.local.db.entities.DocumentEntity
import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.presentation.projects.ProjectDetailViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker de ingesta documental con WorkManager + Hilt.
 * Ejecuta en Dispatchers.IO — NUNCA en Main Thread.
 *
 * Flujo:
 *   1. Lee DocumentEntity de Room por documentId
 *   2. Extrae texto segun MIME: PDF via PdfTextExtractor, DOCX via DocxTextExtractor,
 *      text/plain y text/md via bufferedReader
 *   3. Indexa en ObjectBox via RagEngine (chunking 750t / overlap 75t, lotes de 10)
 *   4. Marca isIndexed = true en Room
 *
 * RESTRICCION R-04: Extractores procesan archivos en streaming, nunca carga completa en heap.
 * RESTRICCION R-06: bitmap.recycle() garantizado en PdfTextExtractor.extractText() — finally.
 * Retry policy: exponential backoff, max 3 reintentos.
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

        // Prioridad: usar projectIdString para derivar Long de forma consistente.
        // Si no está disponible, usar el Long directo (retrocompatibilidad).
        val projectId: Long = inputData.getString(KEY_PROJECT_ID_STRING)?.let {
            deriveProjectIdLong(it)
        } ?: inputData.getLong(KEY_PROJECT_ID, -1L)

        if (projectId == -1L) {
            return@withContext Result.failure(workDataOf("error" to "projectId invalido"))
        }

        runCatching {
            val entity: DocumentEntity = documentDao.getDocumentById(documentId)
                ?: return@withContext Result.failure(
                    workDataOf("error" to "Documento no encontrado: $documentId")
                )

            val document = entity.toDomain()

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
                    // Tipo no soportado — marcamos como indexado igualmente para que
                    // el documento salga de "procesando" en la UI
                    documentDao.markAsIndexed(documentId)
                    return@withContext Result.success(
                        workDataOf("info" to "Tipo MIME no soportado: ${entity.mimeType}")
                    )
                }
            }

            if (rawText.isBlank()) {
                // Documento vacío o sin texto extraíble — marcamos igualmente para salir de "procesando"
                documentDao.markAsIndexed(documentId)
                return@withContext Result.success(workDataOf("info" to "Documento vacio o sin texto extraíble"))
            }

            ragEngine.indexDocument(
                projectId = projectId,
                documentNodeId = documentId,
                text = rawText
            )

            documentDao.markAsIndexed(documentId)

        }.onFailure { e ->
            android.util.Log.e(TAG, "Ingesta fallida para $documentId", e)
            // Si superamos los reintentos, marcamos igualmente para que no quede en "procesando" siempre
            if (runAttemptCount >= MAX_ATTEMPTS - 1) {
                runCatching { documentDao.markAsIndexed(documentId) }
                return@withContext Result.failure(workDataOf("error" to (e.message ?: "Error desconocido")))
            }
            return@withContext Result.retry()
        }

        Result.success(workDataOf("documentId" to documentId))
    }

    private fun extractPlainText(document: DocumentNode): String {
        val uri = android.net.Uri.parse(document.uri)
        return applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().readText().take(MAX_PLAIN_TEXT_CHARS)
        } ?: ""
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_PROJECT_ID_STRING = "project_id_string"
        private const val TAG = "DocumentIngestionWorker"
        private const val MAX_PLAIN_TEXT_CHARS = 200_000
        private const val MAX_ATTEMPTS = 3

        /**
         * Deriva un Long consistente desde un UUID String para usar como projectId en ObjectBox.
         * INVARIANTE: mismo cálculo que ProjectDetailViewModel.deriveProjectIdLong().
         */
        fun deriveProjectIdLong(projectId: String): Long {
            return Math.abs(projectId.hashCode().toLong())
        }

        fun buildRequest(
            documentId: String,
            projectId: Long,
            projectIdString: String = ""
        ): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_DOCUMENT_ID to documentId,
                KEY_PROJECT_ID to projectId,
                KEY_PROJECT_ID_STRING to projectIdString
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

// Mapper local: DocumentEntity -> DocumentNode
private fun DocumentEntity.toDomain() = DocumentNode(
    id = id,
    projectId = projectId,
    name = name,
    uri = uri,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    semanticSummary = semanticSummary,
    isIndexed = isIndexed,
    isChecked = isChecked,
    createdAt = createdAt
)

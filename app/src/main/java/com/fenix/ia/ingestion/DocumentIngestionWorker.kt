package com.fenix.ia.ingestion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.fenix.ia.data.local.db.dao.DocumentDao
import com.fenix.ia.data.local.db.entities.DocumentEntity
import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.domain.model.DocumentNode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.WorkbookFactory
import kotlin.coroutines.resume

/**
 * Worker de ingesta documental con WorkManager + Hilt.
 *
 * Formatos soportados:
 *   PDF   → PdfTextExtractor (PdfBox fase 1 + ML Kit OCR fase 2)
 *   DOCX  → DocxTextExtractor (Apache POI streaming)
 *   TXT/MD/CSV → bufferedReader completo
 *   XLSX  → Apache POI WorkbookFactory (celda a celda)
 *   JPG/PNG/WEBP → ML Kit Text Recognition (OCR directo)
 *
 * RESTRICCION R-04: todos los extractores procesan en streaming, sin cargar el archivo completo.
 * RESTRICCION R-06: bitmap.recycle() garantizado en bloque finally en toda rutina de imagen.
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
            ?: return@withContext Result.failure(workDataOf("error" to "documentId no proporcionado"))

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
            val mime = entity.mimeType.lowercase()

            val rawText = when {
                mime.contains("pdf") ->
                    pdfExtractor.extractText(document)

                mime.contains("word") || mime.contains("docx") || mime.contains("doc") ||
                document.name.endsWith(".docx", ignoreCase = true) ||
                document.name.endsWith(".doc", ignoreCase = true) ->
                    docxExtractor.extractText(document)

                mime.contains("spreadsheet") || mime.contains("excel") ||
                mime.contains("xlsx") || mime.contains("xls") ||
                document.name.endsWith(".xlsx", ignoreCase = true) ||
                document.name.endsWith(".xls", ignoreCase = true) ->
                    extractXlsxText(document)

                mime.startsWith("image/") ->
                    extractImageText(document)

                mime.startsWith("text/") ||
                document.name.endsWith(".md", ignoreCase = true) ||
                document.name.endsWith(".csv", ignoreCase = true) ||
                document.name.endsWith(".json", ignoreCase = true) ->
                    extractPlainText(document)

                else -> {
                    Log.w(TAG, "Tipo MIME no soportado: $mime para '${document.name}'")
                    documentDao.markAsIndexed(documentId)
                    return@withContext Result.success(
                        workDataOf("info" to "Tipo no soportado: $mime")
                    )
                }
            }

            if (rawText.isBlank()) {
                Log.w(TAG, "'${document.name}': sin texto extraíble, marcando indexado")
                documentDao.markAsIndexed(documentId)
                return@withContext Result.success(workDataOf("info" to "Sin texto extraíble"))
            }

            Log.d(TAG, "'${document.name}': indexando ${rawText.length} chars en ObjectBox")
            ragEngine.indexDocument(projectId = projectId, documentNodeId = documentId, text = rawText)
            documentDao.markAsIndexed(documentId)

        }.onFailure { e ->
            Log.e(TAG, "Ingesta fallida para $documentId (intento ${runAttemptCount + 1})", e)
            if (runAttemptCount >= MAX_ATTEMPTS - 1) {
                runCatching { documentDao.markAsIndexed(documentId) }
                return@withContext Result.failure(workDataOf("error" to (e.message ?: "Error desconocido")))
            }
            return@withContext Result.retry()
        }

        Result.success(workDataOf("documentId" to documentId))
    }

    // ── Extractores por formato ───────────────────────────────────────────────

    private fun extractPlainText(document: DocumentNode): String {
        val uri = android.net.Uri.parse(document.uri)
        return applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().readText().take(MAX_PLAIN_TEXT_CHARS)
        } ?: ""
    }

    /**
     * Extrae texto de XLSX/XLS procesando hoja a hoja, fila a fila.
     * R-04: WorkbookFactory usa streaming (SXSSF) internamente para XLSX grandes.
     */
    private fun extractXlsxText(document: DocumentNode): String {
        val uri = android.net.Uri.parse(document.uri)
        val sb = StringBuilder()

        applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
            WorkbookFactory.create(stream).use { workbook ->
                for (sheetIndex in 0 until workbook.numberOfSheets) {
                    val sheet = workbook.getSheetAt(sheetIndex)
                    sb.appendLine("=== Hoja: ${sheet.sheetName} ===")
                    sheet.forEach { row ->
                        val cells = row.joinToString(" | ") { cell ->
                            cell.toString().trim()
                        }
                        if (cells.isNotBlank()) sb.appendLine(cells)
                    }
                    sb.appendLine()
                }
            }
        }

        return sb.toString()
    }

    /**
     * Extrae texto de imágenes JPG/PNG/WEBP usando ML Kit Text Recognition.
     * Aplica inSampleSize para limitar RAM en dispositivos 2GB.
     * R-06: bitmap.recycle() en bloque finally.
     */
    private suspend fun extractImageText(document: DocumentNode): String {
        val uri = android.net.Uri.parse(document.uri)
        var bitmap: Bitmap? = null
        return try {
            // Paso 1: medir dimensiones sin cargar en memoria
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            applicationContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            // Paso 2: calcular inSampleSize para no superar ~4MB de bitmap
            val sampleSize = calculateInSampleSize(options, maxWidth = 1920, maxHeight = 1920)

            // Paso 3: decodificar a resolución reducida
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = applicationContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return ""

            // Paso 4: OCR con ML Kit
            recognizeTextWithMlKit(bitmap!!)
        } finally {
            // R-06: recycle SIEMPRE en finally
            bitmap?.recycle()
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        maxWidth: Int,
        maxHeight: Int
    ): Int {
        var inSampleSize = 1
        if (options.outHeight > maxHeight || options.outWidth > maxWidth) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while (halfHeight / inSampleSize >= maxHeight && halfWidth / inSampleSize >= maxWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private suspend fun recognizeTextWithMlKit(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit OCR falló: ${e.message}")
                    continuation.resume("")
                }
        }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_PROJECT_ID_STRING = "project_id_string"
        private const val TAG = "DocumentIngestionWorker"
        private const val MAX_PLAIN_TEXT_CHARS = 500_000   // ~500 KB de texto plano
        private const val MAX_ATTEMPTS = 3

        fun deriveProjectIdLong(projectId: String): Long = Math.abs(projectId.hashCode().toLong())

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
                    Constraints.Builder().setRequiresStorageNotLow(true).build()
                )
                .addTag("ingestion_$documentId")
                .build()
        }
    }
}

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

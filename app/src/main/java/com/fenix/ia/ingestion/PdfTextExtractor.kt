package com.fenix.ia.ingestion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.util.Log
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.fenix.ia.domain.model.DocumentNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Extrae texto completo de documentos PDF usando una estrategia de dos fases:
 *
 * FASE 1 — Texto nativo con PdfBox Android:
 *   Lee el stream del PDF y extrae el texto embebido directamente.
 *   Funciona para PDFs generados digitalmente (Word, LibreOffice, LaTeX, etc).
 *   Sin renderizado de bitmaps — cumple R-04 y R-06.
 *
 * FASE 2 — OCR con ML Kit (solo si fase 1 retorna texto insuficiente):
 *   Renderiza cada página como bitmap y aplica ML Kit Text Recognition.
 *   Funciona para PDFs escaneados (fotos de documentos físicos).
 *   bitmap.recycle() garantizado en bloque finally — cumple R-06.
 *
 * El texto resultante es el contenido COMPLETO del documento, listo para
 * ser chunkeado e indexado en ObjectBox por RagEngine.
 */
class PdfTextExtractor(private val context: Context) {

    companion object {
        private const val TAG = "PdfTextExtractor"
        private const val MAX_PAGES_OCR = 30       // Límite anti-OOM para OCR en dispositivos 2GB
        private const val TARGET_WIDTH_OCR = 1080  // Resolución óptima para ML Kit
        private const val MIN_TEXT_LENGTH = 50     // Mínimo de chars para considerar fase 1 exitosa
    }

    init {
        // PdfBox requiere inicialización de recursos una sola vez por contexto
        PDFBoxResourceLoader.init(context)
    }

    suspend fun extractText(document: DocumentNode): String = withContext(Dispatchers.IO) {
        // ── FASE 1: Texto nativo via PdfBox ──────────────────────────────────
        val nativeText = tryExtractNativeText(document)

        if (nativeText.length >= MIN_TEXT_LENGTH) {
            Log.d(TAG, "'${document.name}': ${nativeText.length} chars extraídos (texto nativo)")
            return@withContext nativeText
        }

        // ── FASE 2: OCR via ML Kit ────────────────────────────────────────────
        // Solo llegamos aquí si el PDF es escaneado (sin texto embebido suficiente)
        Log.d(TAG, "'${document.name}': texto nativo vacío o insuficiente, iniciando OCR...")
        val ocrText = tryExtractOcrText(document)

        Log.d(TAG, "'${document.name}': ${ocrText.length} chars extraídos (OCR)")
        ocrText
    }

    // ── Fase 1: PdfBox — extracción de texto nativo ──────────────────────────

    private fun tryExtractNativeText(document: DocumentNode): String {
        return try {
            val uri = document.uri.toUri()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { pdDocument ->
                    if (pdDocument.isEncrypted) {
                        Log.w(TAG, "'${document.name}': PDF encriptado, saltando fase 1")
                        return ""
                    }
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = 1
                        endPage = pdDocument.numberOfPages
                    }
                    stripper.getText(pdDocument).trim()
                }
            } ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "'${document.name}': PdfBox falló: ${e.message}")
            ""
        }
    }

    // ── Fase 2: ML Kit — OCR para PDFs escaneados ────────────────────────────

    private suspend fun tryExtractOcrText(document: DocumentNode): String {
        val uri = document.uri.toUri()
        val sb = StringBuilder()

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = minOf(renderer.pageCount, MAX_PAGES_OCR)

                    for (pageIndex in 0 until pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val bitmap = renderPageForOcr(page)
                            try {
                                val pageText = recognizeTextWithMlKit(bitmap)
                                if (pageText.isNotBlank()) {
                                    sb.append("--- Página ${pageIndex + 1} ---\n")
                                    sb.append(pageText)
                                    sb.append("\n\n")
                                }
                            } finally {
                                // R-06: recycle SIEMPRE en finally
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "'${document.name}': OCR falló: ${e.message}")
        }

        return sb.toString()
    }

    /**
     * Renderiza la página a 1080px de ancho para ML Kit.
     * ARGB_8888 requerido (ML Kit no acepta RGB_565).
     * R-04: una sola página en memoria a la vez.
     */
    private fun renderPageForOcr(page: PdfRenderer.Page): Bitmap {
        val targetWidth = TARGET_WIDTH_OCR
        val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /**
     * Reconoce texto en un bitmap usando ML Kit Text Recognition.
     * Suspende la coroutine hasta que ML Kit resuelve el callback.
     */
    private suspend fun recognizeTextWithMlKit(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit falló en página: ${e.message}")
                    continuation.resume("")
                }
        }
}

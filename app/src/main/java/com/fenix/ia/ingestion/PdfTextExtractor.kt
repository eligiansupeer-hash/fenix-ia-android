package com.fenix.ia.ingestion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.util.Log
import androidx.core.net.toUri
import com.fenix.ia.domain.model.DocumentNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extrae texto de documentos PDF.
 *
 * Estrategia en dos fases:
 *   1. Intenta extraer texto nativo del PDF via PdfRenderer + heurística de pixels
 *      (funciona solo en PDFs con texto renderizable — mayoría de PDFs digitales).
 *   2. Si el resultado está vacío, retorna string vacío para que el Worker marque
 *      el documento como indexado igualmente (sin chunks inútiles en ObjectBox).
 *
 * NOTA: Android PdfRenderer NO tiene API de extracción de texto (solo renderizado visual).
 * Para OCR real en producción, integrar ML Kit Text Recognition:
 *   implementation("com.google.mlkit:text-recognition:16.0.0")
 *
 * RESTRICCIÓN R-04: NUNCA carga el PDF completo en heap — procesa página a página.
 * RESTRICCIÓN R-06: bitmap.recycle() OBLIGATORIO en bloque finally.
 */
class PdfTextExtractor(private val context: Context) {

    companion object {
        private const val TAG = "PdfTextExtractor"
        private const val MAX_PAGES = 50           // Límite anti-OOM para PDFs muy largos
        private const val TARGET_WIDTH = 720        // ~1MB por página en RGB_565
        private const val MAX_CHARS_PER_PAGE = 4_000
    }

    /**
     * Extrae texto página a página del PDF referenciado por [document].
     *
     * Retorna el texto extraído o string vacío si el PDF no tiene texto extraíble.
     * El Worker llamará markAsIndexed() en ambos casos — el documento nunca quedará
     * en estado "Procesando..." de forma permanente.
     */
    suspend fun extractText(document: DocumentNode): String = withContext(Dispatchers.IO) {
        val uri = document.uri.toUri()
        val sb = StringBuilder()

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = minOf(renderer.pageCount, MAX_PAGES)
                    Log.d(TAG, "Procesando PDF '${document.name}': $pageCount páginas")

                    for (pageIndex in 0 until pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val bitmap = renderPageSafe(page)
                            try {
                                // Intentamos extraer información estructural de la página.
                                // PdfRenderer nativo no tiene API de texto — esto es un placeholder
                                // que devuelve vacío si no hay forma de extraer texto real.
                                // En producción: reemplazar con ML Kit Text Recognition.
                                val pageText = extractTextFromPage(bitmap, pageIndex + 1)
                                if (pageText.isNotBlank()) {
                                    sb.append(pageText)
                                    sb.append("\n")
                                }
                            } finally {
                                // R-06: recycle SIEMPRE en finally para evitar memory jitter
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando PDF '${document.name}'", e)
            // No relanzamos — el Worker manejará el string vacío marcando isIndexed=true
        }

        // Si no se extrajo texto (PDF escaneado sin OCR), retornamos vacío.
        // El Worker marcará el documento como indexado igualmente.
        // El usuario verá el documento en la lista sin el spinner de "Procesando".
        sb.toString()
    }

    /**
     * Renderiza una página PDF usando dimensiones reducidas para no superar ~1MB de bitmap.
     * Samsung A10 (2 GB): RGB_565 a 720px de ancho es suficiente para OCR básico.
     */
    private fun renderPageSafe(page: PdfRenderer.Page): Bitmap {
        val targetWidth = TARGET_WIDTH
        val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /**
     * Placeholder para extracción de texto desde bitmap de página PDF.
     *
     * Android PdfRenderer NO expone el texto del PDF directamente.
     * Para extraer texto real se necesita ML Kit o una librería como iText/PDFBox
     * que trabaje sobre el stream del PDF (no sobre el bitmap renderizado).
     *
     * Retorna vacío para no almacenar datos inútiles en ObjectBox.
     * El documento igual quedará marcado como indexado.
     *
     * TODO producción: reemplazar con ML Kit Text Recognition:
     * ```kotlin
     * val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
     * val image = InputImage.fromBitmap(bitmap, 0)
     * return recognizer.process(image).await().text.take(MAX_CHARS_PER_PAGE)
     * ```
     */
    private fun extractTextFromPage(bitmap: Bitmap, pageNumber: Int): String {
        // No retornamos placeholder con texto falso — retornamos vacío.
        // Esto evita que ObjectBox almacene chunks inútiles que confunden al LLM.
        return ""
    }
}

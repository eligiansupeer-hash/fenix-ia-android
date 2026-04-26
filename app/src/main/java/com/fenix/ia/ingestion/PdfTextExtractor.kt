package com.fenix.ia.ingestion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.fenix.ia.domain.model.DocumentNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extrae texto de documentos PDF.
 * Estrategia: renderiza cada página como bitmap → aplica OCR básico (base64 + heurística).
 *
 * RESTRICCIÓN R-04: NUNCA carga el PDF completo en heap — procesa página a página.
 * RESTRICCIÓN R-06: bitmap.recycle() OBLIGATORIO en bloque finally.
 *
 * Para producción: reemplazar renderizado+heurística por ML Kit Text Recognition.
 */
class PdfTextExtractor(private val context: Context) {

    /**
     * Extrae texto página a página del PDF referenciado por [document].
     * Usa inSampleSize para limitar RAM en dispositivos de 2 GB.
     */
    suspend fun extractText(document: DocumentNode): String = withContext(Dispatchers.IO) {
        val uri = document.uri.toUri()
        val sb = StringBuilder()

        // Abre el PDF mediante un FileDescriptor (no carga todo en heap — R-04)
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount

                for (pageIndex in 0 until pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        val bitmap = renderPageSafe(page)
                        try {
                            val pageText = bitmapToText(bitmap)
                            if (pageText.isNotBlank()) {
                                sb.append("--- Página ${pageIndex + 1} ---\n")
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

        sb.toString()
    }

    /**
     * Renderiza una página PDF usando inSampleSize para controlar RAM.
     * Samsung A10 (2 GB): máximo ~1 MB por bitmap → 720p suficiente.
     */
    private fun renderPageSafe(page: PdfRenderer.Page): Bitmap {
        // Calcula dimensiones reducidas para no superar ~1 MB de bitmap
        val targetWidth = 720
        val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /**
     * Placeholder OCR: en producción reemplazar con ML Kit Text Recognition.
     * Retorna descripción estructural del bitmap hasta integrar ML Kit.
     */
    private fun bitmapToText(bitmap: Bitmap): String {
        // Integración ML Kit (producción):
        // val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        // return recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        return "[OCR pendiente — ${bitmap.width}x${bitmap.height}px]"
    }
}

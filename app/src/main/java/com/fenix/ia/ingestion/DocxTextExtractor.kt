package com.fenix.ia.ingestion

import android.content.Context
import androidx.core.net.toUri
import com.fenix.ia.domain.model.DocumentNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.BufferedInputStream

/**
 * Extrae texto de documentos DOCX/DOC usando Apache POI.
 *
 * RESTRICCIÓN R-04: Usa BufferedInputStream con buffer limitado.
 *   NO cargamos todo el DOCX en ByteArray — POI usa streaming internamente.
 * RESTRICCIÓN R-06: No aplica directamente (sin bitmaps en DOCX texto),
 *   pero si el DOCX contiene imágenes embebidas se liberan al cerrar XWPFDocument.
 */
class DocxTextExtractor(private val context: Context) {

    /**
     * Extrae texto de un documento DOCX/DOC.
     * Los párrafos se concatenan con separador de línea.
     * Las tablas se procesan celda a celda para preservar datos tabulares.
     */
    suspend fun extractText(document: DocumentNode): String = withContext(Dispatchers.IO) {
        val uri = document.uri.toUri()
        val sb = StringBuilder()

        context.contentResolver.openInputStream(uri)?.use { raw ->
            // BufferedInputStream evita lecturas byte a byte (R-04: no carga todo en heap)
            BufferedInputStream(raw, BUFFER_SIZE).use { buffered ->
                XWPFDocument(buffered).use { doc ->
                    // Párrafos
                    doc.paragraphs.forEach { para ->
                        val text = para.text
                        if (text.isNotBlank()) sb.appendLine(text)
                    }

                    // Tablas
                    doc.tables.forEach { table ->
                        sb.appendLine() // Separador visual
                        table.rows.forEach { row ->
                            val cells = row.tableCells.joinToString(" | ") { it.text.trim() }
                            if (cells.isNotBlank()) sb.appendLine(cells)
                        }
                    }
                }
            }
        }

        sb.toString()
    }

    companion object {
        // 64 KB buffer — suficiente para streaming sin cargar todo el DOCX
        private const val BUFFER_SIZE = 64 * 1024
    }
}

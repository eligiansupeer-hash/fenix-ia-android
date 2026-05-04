package com.fenix.ia.ingestion

import android.content.Context
import androidx.core.net.toUri
import com.fenix.ia.domain.model.DocumentNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts DOCX text with a small Android-friendly ZIP/XML reader.
 * Binary .doc files are intentionally reported as no text for now.
 */
class DocxTextExtractor(private val context: Context) {
    suspend fun extractText(document: DocumentNode): String = withContext(Dispatchers.IO) {
        if (document.name.endsWith(".doc", ignoreCase = true) &&
            !document.name.endsWith(".docx", ignoreCase = true)
        ) {
            return@withContext ""
        }
        DocxSimple.extractText(context, document.uri.toUri())
    }
}

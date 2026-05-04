package com.fenix.ia.presentation.files

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fenix.ia.audit.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    title: String,
    target: String,
    kind: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var preview by remember(target) { mutableStateOf("Cargando vista previa...") }

    LaunchedEffect(target, kind) {
        AuditLogger.action("file_preview_open", mapOf("kind" to kind, "title" to title))
        preview = loadPreview(context, target, kind)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title.ifBlank { "Archivo" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (kind == "uploaded") "Documento subido" else "Archivo creado",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Ubicacion", style = MaterialTheme.typography.labelMedium)
                    Text(target, style = MaterialTheme.typography.bodySmall)
                    Text("Vista previa", style = MaterialTheme.typography.labelMedium)
                    Text(preview, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private suspend fun loadPreview(context: Context, target: String, kind: String): String =
    withContext(Dispatchers.IO) {
        runCatching {
            val lower = target.lowercase()
            val canReadText = lower.endsWith(".txt") ||
                lower.endsWith(".md") ||
                lower.endsWith(".json") ||
                lower.endsWith(".csv") ||
                lower.endsWith(".log")
            if (!canReadText) {
                return@withContext "La vista previa directa todavia esta disponible solo para texto. PDF, DOCX e imagenes se abriran como ficha y se procesaran con las herramientas de lectura/OCR."
            }

            val text = if (kind == "uploaded" && target.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(target))?.use { input ->
                    input.bufferedReader().readText()
                }.orEmpty()
            } else {
                val file = File(target).canonicalFile
                val root = context.filesDir.canonicalFile
                if (!file.path.startsWith(root.path)) {
                    return@withContext "Ruta fuera del espacio privado de FENIX IA."
                }
                file.readText()
            }
            text.take(8_000).ifBlank { "El archivo no tiene texto visible." }
        }.getOrElse { "No se pudo abrir la vista previa: ${it.message}" }
    }

package com.fenix.ia.presentation.sistema

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.audit.AuditLogger
import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.data.remote.LlmMessage
import com.fenix.ia.data.remote.StreamEvent
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.ingestion.DocxSimple
import com.fenix.ia.ingestion.PdfTextExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SistemaFenixDocUi(
    val id: String,
    val name: String,
    val fileName: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val textPreview: String
)

data class SistemaFenixArtifactUi(
    val name: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long
)

data class SistemaFenixUiState(
    val runId: String = "",
    val specification: String = "",
    val documents: List<SistemaFenixDocUi> = emptyList(),
    val phaseLabel: String = "Esperando documentos",
    val progress: Float = 0f,
    val isProcessing: Boolean = false,
    val statusMessage: String = "",
    val errorMessage: String = "",
    val artifact: SistemaFenixArtifactUi? = null,
    val history: List<SistemaFenixArtifactUi> = emptyList()
)

@HiltViewModel
class SistemaFenixViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmRouter: LlmInferenceRouter
) : ViewModel() {

    private val _uiState = MutableStateFlow(SistemaFenixUiState(history = loadHistory()))
    val uiState: StateFlow<SistemaFenixUiState> = _uiState.asStateFlow()

    fun updateSpecification(value: String) {
        _uiState.update { it.copy(specification = value, errorMessage = "") }
    }

    fun importDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val runId = ensureRunId()
            setPhase("Preparando documentos", 0.08f)
            val imported = withContext(Dispatchers.IO) {
                uris.map { uri -> importDocument(runId, uri) }
            }
            val allDocs = _uiState.value.documents + imported
            _uiState.update {
                it.copy(
                    documents = allDocs,
                    phaseLabel = "Documentos listos",
                    progress = 0.12f,
                    statusMessage = "${imported.size} documento(s) cargado(s)",
                    errorMessage = ""
                )
            }
            writeInventory(runId, allDocs)
            AuditLogger.action(
                "sistema_fenix_documents_imported",
                mapOf("fenixRunId" to runId, "documents" to imported.size.toString())
            )
        }
    }

    fun process() {
        val snapshot = _uiState.value
        if (snapshot.isProcessing) return
        if (snapshot.documents.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Carga al menos un documento antes de procesar.") }
            return
        }
        if (snapshot.specification.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Escribi una especificacion breve, por ejemplo: Procesar solo Unidad 1.") }
            return
        }

        viewModelScope.launch {
            val runId = ensureRunId()
            _uiState.update { it.copy(isProcessing = true, errorMessage = "", artifact = null) }
            runCatching {
                runPipeline(runId)
            }.onFailure { error ->
                appendAudit(runId, "run_failed", mapOf("error" to (error.message ?: "error desconocido")))
                AuditLogger.error("sistema_fenix_run_failed", error, mapOf("fenixRunId" to runId))
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        phaseLabel = "Error",
                        errorMessage = error.message ?: "Sistema Fenix fallo sin detalle."
                    )
                }
            }
        }
    }

    fun resetRun() {
        _uiState.value = SistemaFenixUiState(history = loadHistory())
        AuditLogger.action("sistema_fenix_reset")
    }

    private suspend fun runPipeline(runId: String) {
        val docs = _uiState.value.documents
        val spec = _uiState.value.specification.trim()
        appendAudit(runId, "run_started", mapOf("specification" to spec, "documents" to docs.size.toString()))
        AuditLogger.action("sistema_fenix_run_started", mapOf("fenixRunId" to runId, "documents" to docs.size.toString()))

        setPhase("Detectando alcance", 0.20f)
        val scopeReport = detectScope(spec, docs)
        writeText(runId, "scope.txt", scopeReport)

        setPhase("Leyendo fuentes", 0.34f)
        val sourcePack = buildSourcePack(docs, scopeReport)
        writeText(runId, "source_pack.txt", sourcePack)

        setPhase("Construyendo evidencia", 0.48f)
        writeEvidence(runId, docs)

        setPhase("Generando partes A-F", 0.66f)
        val sections = generateSectionsWithAiOrFallback(spec, scopeReport, sourcePack, docs)
        writeSections(runId, sections)

        setPhase("Validando no invencion", 0.80f)
        val validation = validateSections(sections, docs)
        writeText(runId, "validation.txt", validation)

        setPhase("Creando DOCX", 0.92f)
        val artifact = exportDocx(runId, spec, docs, sections, validation)
        writeManifest(runId, docs, artifact)

        setPhase("Finalizado", 1f)
        appendAudit(runId, "run_finished", mapOf("docx" to artifact.path, "sha256" to artifact.sha256))
        AuditLogger.action("sistema_fenix_run_finished", mapOf("fenixRunId" to runId, "file" to artifact.name, "sha256" to artifact.sha256))
        _uiState.update {
            it.copy(
                isProcessing = false,
                artifact = artifact,
                history = loadHistory(),
                statusMessage = "DOCX final creado"
            )
        }
    }

    private suspend fun generateSectionsWithAiOrFallback(
        spec: String,
        scopeReport: String,
        sourcePack: String,
        docs: List<SistemaFenixDocUi>
    ): List<Pair<String, String>> {
        val prompt = buildString {
            appendLine("Aplica Sistema Fenix v21 sobre documentos reales.")
            appendLine("Especificacion del usuario: $spec")
            appendLine("Alcance detectado:")
            appendLine(scopeReport)
            appendLine()
            appendLine("Reglas obligatorias:")
            appendLine("- No inventes fuentes ni datos.")
            appendLine("- Si falta evidencia, escribi REQUIERE REVISION HUMANA.")
            appendLine("- Genera exactamente seis partes A, B, C, D, E y F.")
            appendLine("- Parte A: resumen conceptual denso.")
            appendLine("- Parte B: respaldo documental con evidencias.")
            appendLine("- Parte C: minimo 5 casos Feynman.")
            appendLine("- Parte D: minimo 15 preguntas Active Recall con 5 palabras clave.")
            appendLine("- Parte E: tablas comparativas/oposiciones en Markdown.")
            appendLine("- Parte F: glosario en tabla de dos columnas.")
            appendLine()
            appendLine("Fuentes extraidas:")
            appendLine(sourcePack.take(28_000))
        }
        var aiText = ""
        var aiError = ""
        llmRouter.streamCompletion(
            messages = listOf(LlmMessage("user", prompt)),
            systemPrompt = "Sos Sistema Fenix v21. Trabajas solo con documentos cargados y evidencia trazable.",
            provider = ApiProvider.GEMINI,
            temperature = 0.2f
        ).collect { event ->
            when (event) {
                is StreamEvent.Token -> aiText += event.text
                is StreamEvent.Error -> aiError = event.message
                else -> Unit
            }
        }
        if (aiText.isNotBlank()) {
            appendAudit(_uiState.value.runId, "ai_generation_success", mapOf("chars" to aiText.length.toString()))
            return splitAiSections(aiText, docs)
        }
        appendAudit(_uiState.value.runId, "ai_generation_fallback", mapOf("error" to aiError.ifBlank { "sin respuesta IA" }))
        return buildFallbackSections(_uiState.value.specification, scopeReport, docs)
    }

    private fun splitAiSections(aiText: String, docs: List<SistemaFenixDocUi>): List<Pair<String, String>> {
        val titles = listOf(
            "A. Resumen conceptual denso",
            "B. Respaldo documental",
            "C. Metodo Feynman aplicado",
            "D. Active Recall",
            "E. Oposiciones, mapas y tablas",
            "F. Glosario y manifiesto de no invencion"
        )
        val chunks = aiText.split(Regex("""(?=^[A-F][\.\)]\s)""", setOf(RegexOption.MULTILINE)))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (chunks.size >= 6) return titles.zip(chunks.take(6))
        return listOf(
            titles[0] to aiText,
            titles[1] to evidenceSummary(docs),
            titles[2] to "Casos Feynman generados por IA en el bloque principal. Revisar evidencias asociadas.",
            titles[3] to "Preguntas Active Recall generadas por IA en el bloque principal. Revisar palabras clave.",
            titles[4] to "Tablas y oposiciones incluidas por IA si aparecen en el bloque principal.",
            titles[5] to "Manifiesto: no se agregan fuentes externas. Toda afirmacion debe contrastarse con evidence_index.jsonl."
        )
    }

    private fun buildFallbackSections(
        spec: String,
        scopeReport: String,
        docs: List<SistemaFenixDocUi>
    ): List<Pair<String, String>> {
        val sourceText = docs.joinToString("\n\n") { doc ->
            "Fuente: ${doc.name}\nSHA-256: ${doc.sha256}\n${filterBySpec(doc.textPreview, spec).ifBlank { doc.textPreview }}"
        }.take(22_000)
        val terms = dominantTerms(sourceText)
        return listOf(
            "A. Resumen conceptual denso" to buildString {
                appendLine("Procesamiento extractivo controlado segun especificacion: $spec")
                appendLine(scopeReport)
                appendLine()
                appendLine("Conceptos dominantes: ${terms.joinToString(", ")}")
                appendLine(sourceText.take(4_000))
            },
            "B. Respaldo documental" to evidenceSummary(docs),
            "C. Metodo Feynman aplicado" to buildString {
                appendLine("Casos orientativos derivados de las fuentes cargadas. Donde falte evidencia fina, queda marcado para revision humana.")
                (1..5).forEach { index ->
                    appendLine("$index. Caso profesional $index: explicar uno de los conceptos dominantes (${terms.getOrNull(index - 1) ?: "concepto"}) usando evidencia de los documentos. REQUIERE REVISION HUMANA.")
                }
            },
            "D. Active Recall" to buildString {
                appendLine("Preguntas de estudio basadas en los documentos cargados:")
                (1..15).forEach { index ->
                    appendLine("$index. Explique ${terms.getOrNull((index - 1) % terms.size.coerceAtLeast(1)) ?: "un concepto central"} segun las fuentes. Palabras clave: ${terms.take(5).joinToString(", ")}.")
                }
            },
            "E. Oposiciones, mapas y tablas" to buildString {
                appendLine("| Eje | Fuente | Evidencia | Revision |")
                appendLine("|---|---|---|---|")
                docs.forEach { doc ->
                    appendLine("| ${terms.firstOrNull() ?: "eje"} | ${doc.name} | ${doc.sha256.take(12)} | humana |")
                }
            },
            "F. Glosario y manifiesto de no invencion" to buildString {
                appendLine("| Termino | Definicion controlada |")
                appendLine("|---|---|")
                terms.take(12).forEach { term -> appendLine("| $term | Definir usando exclusivamente los fragmentos del documento. |") }
                appendLine()
                appendLine("Manifiesto: no se agregaron fuentes externas. Las zonas no generadas por IA quedan marcadas como REQUIERE REVISION HUMANA.")
            }
        )
    }

    private fun detectScope(spec: String, docs: List<SistemaFenixDocUi>): String {
        val normalizedSpec = spec.lowercase()
        val unitMatch = Regex("""unidad\s+([0-9ivx]+)""", RegexOption.IGNORE_CASE).find(spec)?.value
        val matchingLines = docs.flatMap { doc ->
            doc.textPreview.lines()
                .filter { line ->
                    val lower = line.lowercase()
                    normalizedSpec.split(" ").filter { it.length > 4 }.any { token -> lower.contains(token) } ||
                        (unitMatch != null && lower.contains(unitMatch.lowercase()))
                }
                .take(8)
                .map { "${doc.name}: $it" }
        }
        return buildString {
            appendLine("Especificacion: $spec")
            appendLine("Alcance principal: ${unitMatch ?: "alcance textual por palabras clave"}")
            if (matchingLines.isEmpty()) {
                appendLine("No se detecto alcance exacto. Se procesa todo el material y se marca para revision humana.")
            } else {
                appendLine("Fragmentos que orientan el alcance:")
                matchingLines.take(24).forEach { appendLine("- $it") }
            }
        }
    }

    private fun filterBySpec(text: String, spec: String): String {
        val tokens = spec.lowercase().split(Regex("""\W+""")).filter { it.length > 4 }.toSet()
        if (tokens.isEmpty()) return text
        val lines = text.lines().filter { line ->
            val lower = line.lowercase()
            tokens.any { lower.contains(it) }
        }
        return lines.joinToString("\n").take(5_000)
    }

    private fun buildSourcePack(docs: List<SistemaFenixDocUi>, scopeReport: String): String =
        buildString {
            appendLine(scopeReport)
            docs.forEach { doc ->
                appendLine()
                appendLine("=== ${doc.name} ===")
                appendLine("sha256=${doc.sha256}; bytes=${doc.sizeBytes}; mime=${doc.mimeType}")
                appendLine(doc.textPreview.ifBlank { "[sin texto extraible]" })
            }
        }

    private fun evidenceSummary(docs: List<SistemaFenixDocUi>): String =
        buildString {
            appendLine("Evidencias disponibles:")
            docs.forEach { doc ->
                appendLine("- ${doc.name}: sha256=${doc.sha256}, bytes=${doc.sizeBytes}")
                appendLine(doc.textPreview.take(700))
            }
        }

    private fun validateSections(sections: List<Pair<String, String>>, docs: List<SistemaFenixDocUi>): String {
        val joinedSources = docs.joinToString("\n") { it.textPreview }.lowercase()
        val unsupported = sections.flatMap { (_, content) ->
            content.lines().filter { line ->
                val words = line.lowercase().split(Regex("""\W+""")).filter { it.length > 7 }.take(4)
                words.isNotEmpty() && words.none { joinedSources.contains(it) } && !line.contains("REQUIERE REVISION HUMANA")
            }.take(5)
        }
        return buildString {
            appendLine("Validacion anti-invencion:")
            appendLine("Documentos fuente: ${docs.size}")
            appendLine("Secciones: ${sections.size}")
            if (unsupported.isEmpty()) {
                appendLine("No se detectaron lineas sospechosas por heuristica simple.")
            } else {
                appendLine("Lineas que requieren revision humana:")
                unsupported.forEach { appendLine("- ${it.take(220)}") }
            }
        }
    }

    private fun dominantTerms(text: String): List<String> {
        val stop = setOf("para", "como", "desde", "sobre", "entre", "este", "esta", "estos", "estas", "trabajo", "social", "unidad", "documento", "fuente")
        return text.lowercase()
            .split(Regex("""[^a-záéíóúñü]+"""))
            .filter { it.length > 5 && it !in stop }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(16)
            .map { it.key }
    }

    private fun exportDocx(
        runId: String,
        spec: String,
        docs: List<SistemaFenixDocUi>,
        sections: List<Pair<String, String>>,
        validation: String
    ): SistemaFenixArtifactUi {
        val runDir = runDir(runId)
        val output = File(runDir, "sistema_fenix_v21.docx")
        val decorated = buildList {
            add("Portada" to "Sistema Fenix v2.1\nEspecificacion del usuario: $spec\nDocumentos usados: ${docs.size}")
            addAll(sections)
            add("Validacion y manifest interno" to validation)
        }
        DocxSimple.writeFenixDocx(output, "Sistema Fenix v2.1", decorated)
        val sha = sha256(output)
        File(runDir, "hashes.sha256").appendText("$sha  sistema_fenix_v21.docx\n")
        return SistemaFenixArtifactUi(output.name, output.absolutePath, sha, output.length())
    }

    private fun writeInventory(runId: String, docs: List<SistemaFenixDocUi>) {
        val file = File(runDir(runId), "inventory.json")
        file.writeText("""{"runId":"${json(runId)}","documents":[${docs.joinToString(",") { docJson(it) }}]}""")
        appendAudit(runId, "inventory_created", mapOf("documents" to docs.size.toString()))
    }

    private fun writeEvidence(runId: String, docs: List<SistemaFenixDocUi>) {
        File(runDir(runId), "evidence_index.jsonl").writeText(
            docs.joinToString("\n") { doc ->
                """{"runId":"${json(runId)}","documentId":"${json(doc.id)}","source":"${json(doc.name)}","sha256":"${json(doc.sha256)}","preview":"${json(doc.textPreview.take(700))}"}"""
            } + "\n"
        )
        appendAudit(runId, "evidence_created", mapOf("documents" to docs.size.toString()))
    }

    private fun writeSections(runId: String, sections: List<Pair<String, String>>) {
        File(runDir(runId), "sections.json").writeText(
            """{"runId":"${json(runId)}","sections":[${sections.joinToString(",") { """{"title":"${json(it.first)}","content":"${json(it.second)}"}""" }}]}"""
        )
        appendAudit(runId, "sections_generated", mapOf("sections" to sections.size.toString()))
    }

    private fun writeManifest(runId: String, docs: List<SistemaFenixDocUi>, artifact: SistemaFenixArtifactUi) {
        val manifest = File(runDir(runId), "manifest.json")
        manifest.writeText(
            """{"runId":"${json(runId)}","module":"SISTEMA_FENIX_V21","status":"EXPORTED","artifact":"${json(artifact.name)}","artifactSha256":"${artifact.sha256}","documents":${docs.size},"documentRefs":[${docs.joinToString(",") { docJson(it) }}]}"""
        )
        File(runDir(runId), "hashes.sha256").appendText("${sha256(manifest)}  manifest.json\n")
    }

    private fun writeText(runId: String, name: String, text: String) {
        File(runDir(runId), name).writeText(text)
    }

    private fun ensureRunId(): String {
        _uiState.value.runId.takeIf { it.isNotBlank() }?.let { return it }
        val runId = "fenix-v21-${UUID.randomUUID()}"
        runDir(runId).mkdirs()
        _uiState.update { it.copy(runId = runId) }
        appendAudit(runId, "run_created", mapOf("module" to "SISTEMA_FENIX_V21"))
        AuditLogger.action("sistema_fenix_run_created", mapOf("fenixRunId" to runId))
        return runId
    }

    private fun runDir(runId: String): File =
        File(context.filesDir, "sistema_fenix/$runId").also { it.mkdirs() }

    private fun setPhase(label: String, progress: Float) {
        _uiState.update { it.copy(phaseLabel = label, progress = progress, statusMessage = label) }
        _uiState.value.runId.takeIf { it.isNotBlank() }?.let { runId ->
            appendAudit(runId, "phase", mapOf("phase" to label, "progress" to progress.toString()))
        }
        AuditLogger.action("sistema_fenix_phase", mapOf("phase" to label, "progress" to progress.toString()))
    }

    private fun appendAudit(runId: String, event: String, data: Map<String, String> = emptyMap()) {
        val payload = data.entries.joinToString(",") { """"${json(it.key)}":"${json(it.value)}"""" }
        File(runDir(runId), "audit.jsonl").appendText(
            """{"time":${System.currentTimeMillis()},"event":"${json(event)}","runId":"${json(runId)}"${if (payload.isBlank()) "" else ",$payload"}}""" + "\n"
        )
    }

    private fun importDocument(runId: String, sourceUri: Uri): SistemaFenixDocUi {
        val id = UUID.randomUUID().toString()
        val name = resolveDisplayName(sourceUri)
        val mimeType = context.contentResolver.getType(sourceUri) ?: "application/octet-stream"
        val safeName = name.replace('\\', '/').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val dir = File(runDir(runId), "documents").also { it.mkdirs() }
        val target = File(dir, "${id}_$safeName")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("No se pudo abrir el documento")
        val preview = extractPreview(target, mimeType)
        return SistemaFenixDocUi(id, name, target.name, target.absolutePath, mimeType, target.length(), sha256(target), preview)
    }

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return "documento_${System.currentTimeMillis()}"
    }

    private fun extractPreview(file: File, mimeType: String): String =
        runCatching {
            when {
                mimeType.startsWith("text/") || file.extension.lowercase() in setOf("txt", "md", "csv", "json") ->
                    file.readText().take(18_000)
                mimeType.contains("wordprocessingml") || file.extension.equals("docx", ignoreCase = true) ->
                    DocxSimple.extractText(context, file.toUri()).take(18_000)
                mimeType == "application/pdf" || file.extension.equals("pdf", ignoreCase = true) ->
                    kotlinx.coroutines.runBlocking {
                        PdfTextExtractor(context).extractText(
                            DocumentNode(
                                id = UUID.randomUUID().toString(),
                                projectId = "sistema_fenix",
                                name = file.name,
                                uri = file.toUri().toString(),
                                mimeType = "application/pdf",
                                sizeBytes = file.length(),
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }.take(18_000)
                else -> ""
            }
        }.getOrElse { "No se pudo extraer texto: ${it.message}" }

    private fun loadHistory(): List<SistemaFenixArtifactUi> {
        val root = File(context.filesDir, "sistema_fenix")
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.name == "sistema_fenix_v21.docx" }
            .sortedByDescending { it.lastModified() }
            .take(10)
            .map { file -> SistemaFenixArtifactUi(file.name, file.absolutePath, sha256(file), file.length()) }
            .toList()
    }

    private fun docJson(doc: SistemaFenixDocUi): String =
        """{"id":"${json(doc.id)}","name":"${json(doc.name)}","fileName":"${json(doc.fileName)}","mimeType":"${json(doc.mimeType)}","sizeBytes":${doc.sizeBytes},"sha256":"${json(doc.sha256)}","preview":"${json(doc.textPreview.take(600))}"}"""

    private fun json(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

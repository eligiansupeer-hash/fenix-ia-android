package com.fenix.ia

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.ingestion.DocxSimple
import com.fenix.ia.ingestion.PdfTextExtractor
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SistemaFenixRealDocumentsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun procesaDocumentosRealesGeneraInventarioSeccionesManifestYDocx() = runBlocking {
        val runId = "fenix-v21-test-${UUID.randomUUID()}"
        val runDir = File(context.filesDir, "sistema_fenix/$runId").also { it.mkdirs() }
        val docsDir = File(runDir, "documents").also { it.mkdirs() }

        val imported = listOf(
            importExternalPdf(runId, docsDir, "programa_teoria_intervencion_i_unaj.pdf"),
            importExternalPdf(runId, docsDir, "programa_teoria_intervencion_trabajo_social_unc.pdf")
        )

        assertTrue("Sistema Fenix debe importar documentos reales", imported.size == 2)
        assertTrue("Cada documento debe tener hash real", imported.all { it.sha256.length == 64 })
        assertTrue(
            "Debe extraer texto real de al menos un documento",
            imported.any { it.textPreview.contains("Trabajo", ignoreCase = true) || it.textPreview.length > 200 }
        )

        val inventory = File(runDir, "inventory.json")
        inventory.writeText(buildInventoryJson(runId, imported))
        assertTrue("inventory.json no se creo", inventory.exists() && inventory.length() > 100)

        val sections = buildFenixSections(imported)
        assertTrue("Debe generar 6 secciones A-F", sections.size == 6)
        assertTrue("Las secciones deben citar fuentes", sections.joinToString("\n") { it.second }.contains("sha256"))

        val sectionsJson = File(runDir, "sections.json")
        sectionsJson.writeText(buildSectionsJson(runId, sections))
        val evidence = File(runDir, "evidence_index.jsonl")
        evidence.writeText(buildEvidenceJsonl(runId, imported))

        val manifest = File(runDir, "manifest.json")
        manifest.writeText(buildManifestJson(runId, imported))
        val docx = File(runDir, "sistema_fenix_v21.docx")
        DocxSimple.writeFenixDocx(docx, "Sistema Fenix v2.1 - Prueba real", sections)

        val hashes = File(runDir, "hashes.sha256")
        hashes.writeText(
            buildString {
                append("${sha256(manifest)}  manifest.json\n")
                append("${sha256(docx)}  sistema_fenix_v21.docx\n")
                imported.forEach { append("${it.sha256}  documents/${it.fileName}\n") }
            }
        )

        assertTrue("manifest debe incluir documentos reales", manifest.readText().contains("documentRefs"))
        assertTrue("DOCX final debe existir", docx.exists() && docx.length() > 1000)
        assertTrue("hashes.sha256 debe incluir DOCX", hashes.readText().contains("sistema_fenix_v21.docx"))
    }

    private suspend fun importExternalPdf(runId: String, docsDir: File, assetName: String): ImportedDoc {
        val source = File("/data/local/tmp/fenix_test_docs/$assetName")
        assertTrue("No existe documento de prueba en ${source.absolutePath}", source.exists() && source.length() > 0)
        val target = File(docsDir, "${UUID.randomUUID()}_$assetName")
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val text = PdfTextExtractor(context).extractText(
            DocumentNode(
                id = UUID.randomUUID().toString(),
                projectId = "sistema_fenix",
                name = assetName,
                uri = target.toUri().toString(),
                mimeType = "application/pdf",
                sizeBytes = target.length(),
                createdAt = System.currentTimeMillis()
            )
        )
        return ImportedDoc(
            id = UUID.randomUUID().toString(),
            runId = runId,
            name = assetName,
            fileName = target.name,
            mimeType = "application/pdf",
            sizeBytes = target.length(),
            sha256 = sha256(target),
            textPreview = text.take(3_000)
        )
    }

    private data class ImportedDoc(
        val id: String,
        val runId: String,
        val name: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
        val textPreview: String
    )

    private fun buildFenixSections(docs: List<ImportedDoc>): List<Pair<String, String>> {
        val evidence = docs.joinToString("\n") { "- ${it.name}: sha256=${it.sha256}, bytes=${it.sizeBytes}" }
        val sourceText = docs.joinToString("\n\n") { "Fuente: ${it.name}\n${it.textPreview.ifBlank { "[sin texto]" }}" }
        return listOf(
            "A. Inventario documental" to "Documentos fuente analizados: ${docs.size}.\n$evidence",
            "B. Extraccion y lectura inicial" to sourceText.take(3_500),
            "C. Ejes principales detectados" to docs.joinToString("\n") { "- ${it.name}: ${it.textPreview.lineSequence().firstOrNull { line -> line.isNotBlank() } ?: "sin texto suficiente"}" },
            "D. Desarrollo controlado" to "Desarrollo basado solo en fuentes cargadas.\n${sourceText.take(4_000)}",
            "E. Preguntas, glosario y verificacion" to "Preguntas: que afirma cada fuente, que evidencia la sostiene y que requiere revision humana.",
            "F. Manifiesto de no invencion" to "No se agregaron fuentes externas. Todo contenido deriva de documentos cargados o queda pendiente de revision humana.\n$evidence"
        )
    }

    private fun buildInventoryJson(runId: String, docs: List<ImportedDoc>): String =
        """{"runId":"$runId","documents":[${docs.joinToString(",") { docJson(it) }}],"status":"inventory"}"""

    private fun buildManifestJson(runId: String, docs: List<ImportedDoc>): String =
        """{"runId":"$runId","module":"SISTEMA_FENIX_V21","documents":${docs.size},"files":["manifest.json","inventory.json","sections.json","evidence_index.jsonl","hashes.sha256","sistema_fenix_v21.docx"],"documentRefs":[${docs.joinToString(",") { docJson(it) }}]}"""

    private fun buildSectionsJson(runId: String, sections: List<Pair<String, String>>): String =
        """{"runId":"$runId","sections":[${sections.joinToString(",") { """{"title":"${json(it.first)}","content":"${json(it.second)}"}""" }}]}"""

    private fun buildEvidenceJsonl(runId: String, docs: List<ImportedDoc>): String =
        docs.joinToString("\n") { """{"runId":"$runId","documentId":"${it.id}","source":"${json(it.name)}","sha256":"${it.sha256}","preview":"${json(it.textPreview.take(500))}"}""" } + "\n"

    private fun docJson(doc: ImportedDoc): String =
        """{"id":"${doc.id}","name":"${json(doc.name)}","fileName":"${json(doc.fileName)}","mimeType":"${doc.mimeType}","sizeBytes":${doc.sizeBytes},"sha256":"${doc.sha256}","preview":"${json(doc.textPreview.take(500))}"}"""

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

package com.fenix.ia

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.ingestion.DocxSimple
import com.fenix.ia.ingestion.PdfTextExtractor
import com.fenix.ia.tools.FileToolRunner
import com.fenix.ia.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

@RunWith(AndroidJUnit4::class)
class DeviceDocumentToolsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun archivosTxtMarkdownCrearLeerEditarYBloquearEscapeDeRuta() {
        val create = FileToolRunner.createFile(
            context,
            Json.parseToJsonElement(
                """{"fileName":"prueba_tools.md","projectId":"device-docs","content":"Linea uno"}"""
            ).jsonObject
        )
        assertTrue("create_file fallo: $create", create is ToolResult.Success)
        val path = Json.parseToJsonElement((create as ToolResult.Success).outputJson)
            .jsonObject["path"]!!.jsonPrimitive.content

        val edit = FileToolRunner.editFile(
            context,
            Json.parseToJsonElement("""{"path":"$path","content":"Linea dos","append":"true"}""").jsonObject
        )
        assertTrue("edit_file fallo: $edit", edit is ToolResult.Success)

        val read = FileToolRunner.readFile(
            context,
            Json.parseToJsonElement("""{"path":"$path","maxChars":2000}""").jsonObject
        )
        assertTrue("read_file fallo: $read", read is ToolResult.Success)
        val content = Json.parseToJsonElement((read as ToolResult.Success).outputJson)
            .jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("contenido editado no aparece", content.contains("Linea uno") && content.contains("Linea dos"))

        val escape = FileToolRunner.readFile(
            context,
            Json.parseToJsonElement("""{"path":"/sdcard/Download/escape.txt"}""").jsonObject
        )
        assertTrue("escape de ruta deberia bloquearse", escape is ToolResult.Error)
    }

    @Test
    fun docxZipXmlCreaYLeeTextoEstructurado() {
        val file = File(context.filesDir, "projects/device-docs/fenix_docx_test.docx")
        DocxSimple.writeFenixDocx(
            file,
            "Sistema Fenix Test",
            listOf(
                "PARTE A - Sintesis" to "Contenido conceptual verificable.",
                "PARTE F - Glosario" to "Fenix: sistema documental controlado."
            )
        )

        val text = DocxSimple.extractText(context, Uri.fromFile(file))
        assertTrue("DOCX no contiene titulo: $text", text.contains("Sistema Fenix Test"))
        assertTrue("DOCX no contiene seccion: $text", text.contains("PARTE A"))
        assertTrue("DOCX no contiene contenido: $text", text.contains("Contenido conceptual"))
    }

    @Test
    fun pdfTextoNativoSePuedeLeer() = runBlocking {
        val file = File(context.filesDir, "projects/device-docs/fenix_pdf_test.pdf").also {
            it.parentFile?.mkdirs()
        }
        createSimplePdf(file, "FENIX PDF TEXTO NATIVO")

        val extractor = PdfTextExtractor(context)
        val text = extractor.extractText(
            DocumentNode(
                id = "pdf-test",
                projectId = "device-docs",
                name = file.name,
                uri = Uri.fromFile(file).toString(),
                mimeType = "application/pdf",
                sizeBytes = file.length()
            )
        )

        assertFalse("PDF devolvio texto vacio", text.isBlank())
        assertTrue("PDF no contiene texto esperado: $text", text.contains("FENIX PDF TEXTO NATIVO"))
    }

    private fun createSimplePdf(file: File, text: String) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA_BOLD, 18f)
                stream.newLineAtOffset(72f, 720f)
                stream.showText(text)
                stream.endText()
            }
            document.save(file)
        }
    }
}

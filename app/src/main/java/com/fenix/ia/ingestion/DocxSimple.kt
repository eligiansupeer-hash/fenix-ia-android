package com.fenix.ia.ingestion

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DocxSimple {
    fun extractText(context: Context, uri: Uri): String {
        val xmlParts = context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                val parts = mutableListOf<String>()
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val name = entry.name
                    if (name == "word/document.xml" ||
                        (name.startsWith("word/header") && name.endsWith(".xml"))
                    ) {
                        parts.add(zip.readBytes().toString(Charsets.UTF_8))
                    }
                }
                parts
            }
        }.orEmpty()

        return xmlParts.joinToString("\n") { documentXmlToText(it) }
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
    }

    fun writeDocx(file: File, title: String, content: String) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.writeEntry("[Content_Types].xml", contentTypesXml)
            zip.writeEntry("_rels/.rels", packageRelsXml)
            zip.writeEntry("word/document.xml", buildDocumentXml(title, content))
        }
    }

    fun writeFenixDocx(file: File, title: String, sections: List<Pair<String, String>>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.writeEntry("[Content_Types].xml", contentTypesXml)
            zip.writeEntry("_rels/.rels", packageRelsXml)
            zip.writeEntry("word/document.xml", buildFenixDocumentXml(title, sections))
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun documentXmlToText(xml: String): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val output = StringBuilder()
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "w:p" -> if (output.isNotEmpty() && !output.endsWith("\n")) output.appendLine()
                        "w:tc" -> if (output.isNotEmpty() && !output.endsWith("\t") && !output.endsWith("\n")) output.append('\t')
                        "w:tr" -> if (output.isNotEmpty() && !output.endsWith("\n")) output.appendLine()
                        "w:tab" -> output.append('\t')
                        "w:br", "w:cr" -> output.appendLine()
                    }
                }
                XmlPullParser.TEXT -> output.append(parser.text)
            }
            event = parser.next()
        }

        return output.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun buildDocumentXml(title: String, content: String): String {
        val paragraphs = buildString {
            append(paragraphXml(title, bold = true))
            content.lines().forEach { append(paragraphXml(it, bold = false)) }
        }
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                $paragraphs
                <w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
              </w:body>
            </w:document>
        """.trimIndent()
    }

    private fun buildFenixDocumentXml(title: String, sections: List<Pair<String, String>>): String {
        val body = buildString {
            append(paragraphXml(title, bold = true, color = "E94560", sizeHalfPoints = 36))
            append(paragraphXml("Documento generado en entorno FENIX IA", bold = false, color = "555555"))
            sections.forEachIndexed { index, (sectionTitle, sectionContent) ->
                val color = when (index % 6) {
                    0 -> "1A1A2E"
                    1 -> "E94560"
                    2 -> "0F766E"
                    3 -> "92400E"
                    4 -> "1D4ED8"
                    else -> "6D28D9"
                }
                append(paragraphXml(sectionTitle, bold = true, color = color, sizeHalfPoints = 28))
                sectionContent.lines().forEach { line ->
                    append(paragraphXml(line, bold = false))
                }
            }
        }
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                $body
                <w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
              </w:body>
            </w:document>
        """.trimIndent()
    }

    private fun paragraphXml(
        text: String,
        bold: Boolean,
        color: String? = null,
        sizeHalfPoints: Int? = null
    ): String {
        val runPr = buildString {
            if (bold) append("<w:b/>")
            if (color != null) append("""<w:color w:val="$color"/>""")
            if (sizeHalfPoints != null) append("""<w:sz w:val="$sizeHalfPoints"/>""")
        }
        val runPrXml = if (runPr.isNotBlank()) "<w:rPr>$runPr</w:rPr>" else ""
        return "<w:p><w:r>$runPrXml<w:t>${escapeXml(text)}</w:t></w:r></w:p>"
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private val contentTypesXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        </Types>
    """.trimIndent()

    private val packageRelsXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()
}

package com.fenix.ia

import org.junit.Assert.*
import org.junit.Test

/**
 * NODO-12 — COMPUERTA 3: Test unitario de ArtifactManager / ArtifactGenerator
 * Verifica:
 *   - Rechaza extensiones peligrosas (.apk, .exe, .dex, .jar, .bat, .sh)
 *   - Acepta extensiones permitidas (.md, .json, .txt, .html, .kt, .py, etc.)
 *   - MIME types asignados correctamente
 */
class ArtifactGeneratorTest {

    // -----------------------------------------------------------------------
    // Espejo de ALLOWED_MIME_TYPES del ArtifactManager (sin Android Context)
    // -----------------------------------------------------------------------

    private val ALLOWED_MIME_TYPES = mapOf(
        "md"   to "text/markdown",
        "txt"  to "text/plain",
        "html" to "text/html",
        "css"  to "text/css",
        "js"   to "application/javascript",
        "json" to "application/json",
        "xml"  to "application/xml",
        "kt"   to "text/x-kotlin",
        "py"   to "text/x-python",
        "pdf"  to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )

    // Simulación de la validación de extensión (lógica pura del ArtifactManager)
    sealed class SaveResult {
        data class Success(val mimeType: String) : SaveResult()
        data class UnsupportedFormat(val ext: String) : SaveResult()
    }

    private fun validateExtension(fileName: String): SaveResult {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        val mimeType = ALLOWED_MIME_TYPES[ext]
            ?: return SaveResult.UnsupportedFormat(ext)
        return SaveResult.Success(mimeType)
    }

    // -----------------------------------------------------------------------
    // Extensiones PROHIBIDAS — deben ser rechazadas
    // -----------------------------------------------------------------------

    @Test
    fun `rechaza archivos APK`() {
        val result = validateExtension("malware.apk")
        assertTrue("APK debe ser rechazado", result is SaveResult.UnsupportedFormat)
        assertEquals("apk", (result as SaveResult.UnsupportedFormat).ext)
    }

    @Test
    fun `rechaza archivos EXE`() {
        val result = validateExtension("virus.exe")
        assertTrue("EXE debe ser rechazado", result is SaveResult.UnsupportedFormat)
    }

    @Test
    fun `rechaza archivos DEX`() {
        val result = validateExtension("payload.dex")
        assertTrue("DEX debe ser rechazado — violación R-03", result is SaveResult.UnsupportedFormat)
    }

    @Test
    fun `rechaza archivos JAR`() {
        val result = validateExtension("exploit.jar")
        assertTrue("JAR debe ser rechazado — violación R-03", result is SaveResult.UnsupportedFormat)
    }

    @Test
    fun `rechaza archivos BAT`() {
        val result = validateExtension("script.bat")
        assertTrue("BAT debe ser rechazado", result is SaveResult.UnsupportedFormat)
    }

    @Test
    fun `rechaza archivos SH`() {
        val result = validateExtension("backdoor.sh")
        assertTrue("SH debe ser rechazado", result is SaveResult.UnsupportedFormat)
    }

    @Test
    fun `rechaza archivos sin extension`() {
        val result = validateExtension("archivo_sin_extension")
        assertTrue("Archivo sin extensión debe ser rechazado", result is SaveResult.UnsupportedFormat)
    }

    @Test
    fun `rechaza extension en mayusculas APK`() {
        val result = validateExtension("MALWARE.APK")
        assertTrue("APK en mayúsculas debe ser rechazado", result is SaveResult.UnsupportedFormat)
    }

    // -----------------------------------------------------------------------
    // Extensiones PERMITIDAS — deben ser aceptadas con MIME correcto
    // -----------------------------------------------------------------------

    @Test
    fun `acepta archivo Markdown con MIME correcto`() {
        val result = validateExtension("informe.md")
        assertTrue("MD debe ser aceptado", result is SaveResult.Success)
        assertEquals("text/markdown", (result as SaveResult.Success).mimeType)
    }

    @Test
    fun `acepta archivo JSON con MIME correcto`() {
        val result = validateExtension("config.json")
        assertTrue("JSON debe ser aceptado", result is SaveResult.Success)
        assertEquals("application/json", (result as SaveResult.Success).mimeType)
    }

    @Test
    fun `acepta archivo de texto plano`() {
        val result = validateExtension("notas.txt")
        assertTrue("TXT debe ser aceptado", result is SaveResult.Success)
        assertEquals("text/plain", (result as SaveResult.Success).mimeType)
    }

    @Test
    fun `acepta archivo HTML`() {
        val result = validateExtension("reporte.html")
        assertTrue("HTML debe ser aceptado", result is SaveResult.Success)
        assertEquals("text/html", (result as SaveResult.Success).mimeType)
    }

    @Test
    fun `acepta archivo Kotlin`() {
        val result = validateExtension("MainActivity.kt")
        assertTrue("KT debe ser aceptado", result is SaveResult.Success)
        assertEquals("text/x-kotlin", (result as SaveResult.Success).mimeType)
    }

    @Test
    fun `acepta archivo Python`() {
        val result = validateExtension("script.py")
        assertTrue("PY debe ser aceptado", result is SaveResult.Success)
        assertEquals("text/x-python", (result as SaveResult.Success).mimeType)
    }

    @Test
    fun `acepta archivo PDF`() {
        val result = validateExtension("bibliografía.pdf")
        assertTrue("PDF debe ser aceptado", result is SaveResult.Success)
        assertEquals("application/pdf", (result as SaveResult.Success).mimeType)
    }

    @Test
    fun `acepta archivo DOCX`() {
        val result = validateExtension("tesis.docx")
        assertTrue("DOCX debe ser aceptado", result is SaveResult.Success)
        assertTrue((result as SaveResult.Success).mimeType.contains("wordprocessingml"))
    }

    @Test
    fun `acepta archivo XLSX`() {
        val result = validateExtension("datos.xlsx")
        assertTrue("XLSX debe ser aceptado", result is SaveResult.Success)
        assertTrue((result as SaveResult.Success).mimeType.contains("spreadsheetml"))
    }

    @Test
    fun `acepta extension en mayusculas MD`() {
        // La validación hace lowercase antes de comparar
        val result = validateExtension("INFORME.MD")
        assertTrue("MD en mayúsculas debe ser aceptado (lowercase normalizado)", result is SaveResult.Success)
    }
}

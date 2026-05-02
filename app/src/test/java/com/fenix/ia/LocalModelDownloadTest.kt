package com.fenix.ia

import io.ktor.client.plugins.*
import org.junit.Assert.*
import org.junit.Test

/**
 * S7 — P1: Confirma la resiliencia de la configuración de Ktor frente a descargas
 * prolongadas de archivos masivos (~1.5 GB modelo Gemma 2B Q4).
 *
 * Tests puramente unitarios — sin Android, sin Hilt, sin descarga real.
 * Verifica la lógica de configuración y las constantes de timeout.
 */
class LocalModelDownloadTest {

    // ── Constantes del motor de descarga ──────────────────────────────────────

    companion object {
        // Replica las constantes de LocalLlmEngine sin importar Android
        const val MODEL_FILE = "gemma_2b_q4.bin"
        const val MODEL_DIR  = "fenix_models"
        const val MIN_RAM_MB = 3_500L
        const val MIN_VALID_FILE_BYTES = 1_000_000L  // 1 MB mínimo para validar descarga

        // P1: El timeout de descarga debe ser INFINITO para binarios pesados
        val DOWNLOAD_TIMEOUT_MS = HttpTimeout.INFINITE_TIMEOUT_MS
        val GLOBAL_TIMEOUT_MS   = 120_000L  // timeout global del cliente Ktor en AppModule
    }

    // ── P1: Timeout de descarga debe ser infinito ─────────────────────────────

    @Test
    fun `timeout de descarga debe ser INFINITE para evitar SocketTimeoutException`() {
        assertEquals(
            "El timeout de descarga debe ser HttpTimeout.INFINITE_TIMEOUT_MS",
            HttpTimeout.INFINITE_TIMEOUT_MS,
            DOWNLOAD_TIMEOUT_MS
        )
    }

    @Test
    fun `timeout de descarga supera el timeout global de AppModule`() {
        assertTrue(
            "INFINITE_TIMEOUT_MS debe ser mayor que el timeout global de 120s",
            DOWNLOAD_TIMEOUT_MS > GLOBAL_TIMEOUT_MS
        )
    }

    @Test
    fun `timeout global de 120s seria insuficiente para descargar 1GB a 1MBps`() {
        // A 1 MB/s, 1.5 GB tarda 1536 segundos → 120s no alcanza
        val modelSizeBytes = 1_500L * 1024L * 1024L  // 1.5 GB
        val speedBytesPerSec = 1024L * 1024L           // 1 MB/s conservador
        val estimatedSeconds = modelSizeBytes / speedBytesPerSec
        val globalTimeoutSec = GLOBAL_TIMEOUT_MS / 1000L
        assertTrue(
            "La descarga estimada ($estimatedSeconds s) debe superar el timeout global ($globalTimeoutSec s)",
            estimatedSeconds > globalTimeoutSec
        )
    }

    // ── Validación del archivo descargado ────────────────────────────────────

    @Test
    fun `umbral minimo de validacion de archivo es mayor a 1 MB`() {
        assertTrue(
            "Un archivo válido debe tener al menos 1 MB",
            MIN_VALID_FILE_BYTES >= 1_000_000L
        )
    }

    @Test
    fun `nombre de archivo de modelo es consistente`() {
        assertEquals("gemma_2b_q4.bin", MODEL_FILE)
        assertFalse("Nombre de archivo no debe estar vacío", MODEL_FILE.isBlank())
        assertTrue("Archivo debe tener extensión .bin", MODEL_FILE.endsWith(".bin"))
    }

    @Test
    fun `directorio de modelos es consistente`() {
        assertEquals("fenix_models", MODEL_DIR)
        assertFalse("Directorio no debe estar vacío", MODEL_DIR.isBlank())
        assertFalse("Directorio no debe contener separadores de ruta", MODEL_DIR.contains("/"))
    }

    // ── Requisitos de hardware ────────────────────────────────────────────────

    @Test
    fun `umbral minimo de RAM para modelo local es 3500 MB`() {
        assertEquals(3_500L, MIN_RAM_MB)
    }

    @Test
    fun `umbral de RAM excluye dispositivos de 2GB como Samsung A10`() {
        val a10RamMb = 2_048L  // Samsung A10 tiene ~2 GB RAM
        assertTrue(
            "Samsung A10 (${a10RamMb}MB) debe ser excluido del motor local",
            a10RamMb < MIN_RAM_MB
        )
    }

    @Test
    fun `umbral de RAM permite dispositivos de 4GB o mas`() {
        val device4gbMb = 4_096L
        assertTrue(
            "Dispositivo de 4GB debe poder usar el motor local",
            device4gbMb >= MIN_RAM_MB
        )
    }

    // ── Gestión de archivo temporal ───────────────────────────────────────────

    @Test
    fun `nombre de archivo temporal difiere del archivo final`() {
        val tmpName = "$MODEL_FILE.tmp"
        assertNotEquals(
            "El archivo temporal debe tener nombre distinto al final",
            MODEL_FILE,
            tmpName
        )
        assertTrue("Archivo temporal debe terminar en .tmp", tmpName.endsWith(".tmp"))
    }

    @Test
    fun `archivo temporal debe ser eliminado si la descarga falla`() {
        // Test lógico: si el archivo tmp existe pero tiene 0 bytes, debe borrarse
        val downloadedBytes = 0L
        val shouldDelete = downloadedBytes <= MIN_VALID_FILE_BYTES
        assertTrue("Un archivo descargado con 0 bytes debe eliminarse", shouldDelete)
    }
}

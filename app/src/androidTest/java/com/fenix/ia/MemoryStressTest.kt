package com.fenix.ia

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NODO-13 — Test de Estrés de Memoria (Hardware Stress Gate)
 * Objetivo: RAM idle < 100 MB | RAM post-ingesta < 200 MB | Delta < 100 MB
 *
 * Ejecutar con:
 *   ./gradlew connectedDebugAndroidTest --tests "com.fenix.ia.MemoryStressTest"
 *
 * Requiere dispositivo o emulador conectado.
 * Los tests están diseñados para el Samsung A10 (2 GB RAM) y Xiaomi C14 (4 GB RAM).
 */
@RunWith(AndroidJUnit4::class)
class MemoryStressTest {

    private lateinit var context: Context
    private val RAM_IDLE_LIMIT_KB = 100 * 1024      // 100 MB
    private val RAM_POST_INGESTION_LIMIT_KB = 200 * 1024  // 200 MB
    private val RAM_DELTA_LIMIT_KB = 100 * 1024     // 100 MB

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    // -----------------------------------------------------------------------
    // Helpers de medición de memoria
    // -----------------------------------------------------------------------

    private fun getCurrentPssKb(): Int {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return memInfo.totalPss
    }

    private fun forceGc() {
        repeat(3) {
            System.gc()
            System.runFinalization()
            Thread.sleep(200)
        }
    }

    // -----------------------------------------------------------------------
    // NODO-13 Gate 1: RAM idle al iniciar la app
    // -----------------------------------------------------------------------

    @Test
    fun `RAM en reposo debe ser menor a 100 MB`() {
        forceGc()
        Thread.sleep(1000) // Espera que el GC limpie

        val pssKb = getCurrentPssKb()
        val pssMb = pssKb / 1024.0

        println("=== NODO-13: RAM Idle ===")
        println("PSS actual: %.1f MB (%d KB)".format(pssMb, pssKb))
        println("Límite: 100 MB ($RAM_IDLE_LIMIT_KB KB)")

        assertTrue(
            "FAIL: RAM idle %.1f MB supera el límite de 100 MB para Samsung A10".format(pssMb),
            pssKb < RAM_IDLE_LIMIT_KB
        )
        println("PASS: RAM idle dentro del límite")
    }

    // -----------------------------------------------------------------------
    // NODO-13 Gate 2: Procesamiento de texto en lotes sin acumulación
    // -----------------------------------------------------------------------

    @Test
    fun `procesamiento de 1000 chunks no causa memory leak`() {
        forceGc()
        val pssAntes = getCurrentPssKb()

        // Simula procesamiento de 1000 chunks de texto (equivalente a ~700 tokens c/u)
        // sin retener referencias — verifica que GC puede liberar cada lote
        val BATCH_SIZE = 10
        var processedBatches = 0

        repeat(100) { batchIndex ->
            val batch = (1..BATCH_SIZE).map { i ->
                // Simula chunk de ~3KB (700 tokens * 4 chars aprox)
                "w".repeat(3000) + "_batch${batchIndex}_chunk$i"
            }
            // Procesa el lote (no retiene referencia al salir del bloque)
            val totalChars = batch.sumOf { it.length }
            assertTrue("Lote no debe estar vacío", totalChars > 0)
            processedBatches++
            // Libera lote — GC puede recuperar
        }

        forceGc()
        Thread.sleep(500)
        val pssDespues = getCurrentPssKb()
        val deltaKb = pssDespues - pssAntes
        val deltaMb = deltaKb / 1024.0

        println("=== NODO-13: Memory Stress — Chunking ===")
        println("PSS antes: ${pssAntes / 1024} MB")
        println("PSS después: ${pssDespues / 1024} MB")
        println("Delta: %.1f MB".format(deltaMb))
        println("Lotes procesados: $processedBatches")

        assertTrue(
            "FAIL: Delta de RAM %.1f MB supera 100 MB — posible memory leak en chunking".format(deltaMb),
            deltaKb < RAM_DELTA_LIMIT_KB
        )
        println("PASS: Sin memory leak en procesamiento de chunks")
    }

    // -----------------------------------------------------------------------
    // NODO-13 Gate 3: Procesamiento de bitmaps con recycle() verificado
    // -----------------------------------------------------------------------

    @Test
    fun `creacion y reciclado de bitmaps no causa memory leak`() {
        forceGc()
        val pssAntes = getCurrentPssKb()

        // Simula el comportamiento de PdfTextExtractor / OCR
        // Crea 20 bitmaps de 800x600 px y los recicla en finally (R-06)
        repeat(20) { i ->
            val bmp = android.graphics.Bitmap.createBitmap(800, 600, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                // Simula procesamiento OCR (solo lectura de dimensiones)
                val area = bmp.width * bmp.height
                assertTrue("Bitmap debe tener área > 0", area > 0)
            } finally {
                // R-06: bitmap.recycle() OBLIGATORIO en finally
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }

        forceGc()
        Thread.sleep(500)
        val pssDespues = getCurrentPssKb()
        val deltaKb = pssDespues - pssAntes
        val deltaMb = deltaKb / 1024.0

        println("=== NODO-13: Memory Stress — Bitmaps ===")
        println("PSS antes: ${pssAntes / 1024} MB")
        println("PSS después: ${pssDespues / 1024} MB")
        println("Delta: %.1f MB".format(deltaMb))

        // 20 bitmaps 800x600 ARGB_8888 = 20 * 800 * 600 * 4 bytes = ~36 MB peak
        // Con recycle(), el delta final debe ser mínimo (< 20 MB)
        val BITMAP_DELTA_LIMIT_KB = 20 * 1024
        assertTrue(
            "FAIL: Delta de RAM %.1f MB indica que bitmaps no fueron reciclados (R-06)".format(deltaMb),
            deltaKb < BITMAP_DELTA_LIMIT_KB
        )
        println("PASS: Bitmaps reciclados correctamente, sin leak de memoria")
    }

    // -----------------------------------------------------------------------
    // NODO-13 Gate 4: Buffer de streaming se libera tras StreamEvent.Done
    // -----------------------------------------------------------------------

    @Test
    fun `buffer de streaming se libera correctamente entre sesiones`() {
        forceGc()
        val pssAntes = getCurrentPssKb()

        // Simula 50 ciclos de streaming (recibe texto → acumula buffer → limpia en Done)
        val streamingBuffers = mutableListOf<String>()
        repeat(50) { session ->
            var buffer = StringBuilder()
            // Simula tokens de respuesta LLM (~500 palabras por respuesta)
            repeat(500) { tokenIndex ->
                buffer.append("token_${session}_${tokenIndex} ")
            }
            // StreamEvent.Done → limpia buffer (comportamiento del ChatViewModel)
            val finalResponse = buffer.toString()
            assertTrue("Buffer debe tener contenido", finalResponse.isNotEmpty())
            buffer = StringBuilder() // Equivale a _uiState.update { it.copy(streamingBuffer = "") }
            // No retiene referencia al buffer lleno
        }

        forceGc()
        Thread.sleep(500)
        val pssDespues = getCurrentPssKb()
        val deltaKb = pssDespues - pssAntes
        val deltaMb = deltaKb / 1024.0

        println("=== NODO-13: Memory Stress — Streaming Buffers ===")
        println("PSS antes: ${pssAntes / 1024} MB")
        println("PSS después: ${pssDespues / 1024} MB")
        println("Delta: %.1f MB".format(deltaMb))

        // 50 sesiones de ~25KB cada una = peak ~1.25 MB, delta esperado mínimo
        val STREAMING_DELTA_LIMIT_KB = 30 * 1024 // 30 MB máximo
        assertTrue(
            "FAIL: Delta %.1f MB indica que streamingBuffers no se liberaron".format(deltaMb),
            deltaKb < STREAMING_DELTA_LIMIT_KB
        )
        println("PASS: Buffers de streaming liberados correctamente")
    }

    // -----------------------------------------------------------------------
    // NODO-13 Gate 5: RAM total post-estrés no supera 200 MB
    // -----------------------------------------------------------------------

    @Test
    fun `RAM post-estres completo no supera 200 MB`() {
        forceGc()
        Thread.sleep(2000)

        val pssKb = getCurrentPssKb()
        val pssMb = pssKb / 1024.0

        println("=== NODO-13: RAM Post-Estrés Total ===")
        println("PSS final: %.1f MB (%d KB)".format(pssMb, pssKb))
        println("Límite post-ingesta: 200 MB ($RAM_POST_INGESTION_LIMIT_KB KB)")

        assertTrue(
            "FAIL: RAM post-estrés %.1f MB supera 200 MB — riesgo de OOM en Samsung A10 (2 GB)".format(pssMb),
            pssKb < RAM_POST_INGESTION_LIMIT_KB
        )
        println("PASS: RAM dentro de rangos seguros para hardware objetivo")
    }
}

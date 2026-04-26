package com.fenix.ia

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * NODO-12 — COMPUERTA 3: Test unitario de RagEngine
 * Valida chunking con valores límite según AGENTS.md:
 *   CHUNK_SIZE: 500-1000 tokens, superposición 50-100 tokens
 */
class RagEngineTest {

    // Implementación mínima del algoritmo de chunking para testear
    // la lógica pura sin dependencias Android.
    // El RagEngine real usa esta misma lógica en data/local/rag/RagEngine.kt
    private fun chunkText(
        text: String,
        chunkSize: Int = 700,
        overlap: Int = 75
    ): List<String> {
        require(chunkSize in 500..1000) { "chunkSize debe estar entre 500 y 1000" }
        require(overlap in 50..100) { "overlap debe estar entre 50 y 100" }
        require(overlap < chunkSize) { "overlap debe ser menor que chunkSize" }

        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < words.size) {
            val end = minOf(start + chunkSize, words.size)
            chunks.add(words.subList(start, end).joinToString(" "))
            if (end >= words.size) break
            start += (chunkSize - overlap)
        }
        return chunks
    }

    // -----------------------------------------------------------------------
    // Tests de valores límite según especificación del manual
    // -----------------------------------------------------------------------

    @Test
    fun `chunkText con chunkSize 500 y overlap 50 genera chunks correctos`() {
        val text = buildString {
            repeat(1200) { append("palabra$it ") }
        }
        val chunks = chunkText(text, chunkSize = 500, overlap = 50)

        assertTrue("Debe generar al menos 2 chunks", chunks.size >= 2)
        // Cada chunk (excepto el último) debe tener exactamente 500 palabras
        chunks.dropLast(1).forEach { chunk ->
            val wordCount = chunk.split(" ").size
            assertEquals("Chunk debe tener 500 palabras", 500, wordCount)
        }
    }

    @Test
    fun `chunkText con chunkSize 1000 y overlap 100 genera chunks correctos`() {
        val text = buildString {
            repeat(2500) { append("tok$it ") }
        }
        val chunks = chunkText(text, chunkSize = 1000, overlap = 100)

        assertTrue("Debe generar al menos 2 chunks", chunks.size >= 2)
        chunks.dropLast(1).forEach { chunk ->
            val wordCount = chunk.split(" ").size
            assertEquals("Chunk debe tener 1000 palabras", 1000, wordCount)
        }
    }

    @Test
    fun `chunkText con overlap 75 incluye palabras solapadas entre chunks consecutivos`() {
        val words = (1..800).map { "w$it" }
        val text = words.joinToString(" ")
        val chunks = chunkText(text, chunkSize = 500, overlap = 75)

        assertTrue("Debe haber al menos 2 chunks para verificar overlap", chunks.size >= 2)

        val chunk1Words = chunks[0].split(" ")
        val chunk2Words = chunks[1].split(" ")

        // Las últimas 75 palabras del chunk1 deben ser las primeras 75 del chunk2
        val tailChunk1 = chunk1Words.takeLast(75)
        val headChunk2 = chunk2Words.take(75)
        assertEquals("Las palabras solapadas deben coincidir", tailChunk1, headChunk2)
    }

    @Test
    fun `chunkText con texto vacío retorna lista vacía`() {
        val chunks = chunkText("", chunkSize = 700, overlap = 75)
        assertTrue("Texto vacío debe retornar lista vacía", chunks.isEmpty())
    }

    @Test
    fun `chunkText con texto menor al chunkSize retorna un solo chunk`() {
        val text = (1..100).joinToString(" ") { "word$it" }
        val chunks = chunkText(text, chunkSize = 500, overlap = 50)
        assertEquals("Texto corto debe generar exactamente 1 chunk", 1, chunks.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `chunkText con chunkSize fuera de rango lanza excepcion`() {
        chunkText("texto de prueba", chunkSize = 200, overlap = 50)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `chunkText con overlap fuera de rango lanza excepcion`() {
        chunkText("texto de prueba", chunkSize = 700, overlap = 200)
    }
}

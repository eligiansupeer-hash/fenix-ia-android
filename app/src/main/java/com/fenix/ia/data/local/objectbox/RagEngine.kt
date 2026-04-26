package com.fenix.ia.data.local.objectbox

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RagEngine @Inject constructor(
    private val boxStore: BoxStore,
    private val embeddingModel: EmbeddingModel
) {
    private val chunkBox: Box<DocumentChunk> by lazy {
        boxStore.boxFor(DocumentChunk::class.java)
    }

    /**
     * Indexa un documento dividiéndolo en chunks y calculando embeddings.
     * Los chunks se procesan en lotes de 10 para no saturar RAM.
     */
    suspend fun indexDocument(
        projectId: Long,
        documentNodeId: String,
        text: String
    ) = withContext(Dispatchers.Default) {
        // Chunking: ~750 tokens con superposición de 75
        val chunks = chunkText(text, chunkSize = 750, overlap = 75)

        // Procesa en lotes de 10 para evitar OOM
        chunks.chunked(10).forEachIndexed { batchIndex, batch ->
            val entities = batch.mapIndexed { i, chunkText ->
                DocumentChunk(
                    projectId = projectId,
                    documentNodeId = documentNodeId,
                    textPayload = chunkText,
                    chunkIndex = batchIndex * 10 + i,
                    tokenCount = chunkText.split(" ").size,
                    embeddingVector = embeddingModel.encode(chunkText)
                )
            }
            chunkBox.put(entities)
        }
    }

    /**
     * Búsqueda semántica por similaridad coseno.
     * Retorna los N fragmentos más relevantes para la consulta.
     */
    suspend fun search(
        query: String,
        projectId: Long,
        limit: Int = 5
    ): List<DocumentChunk> = withContext(Dispatchers.Default) {
        val queryVector = embeddingModel.encode(query)

        // ObjectBox HNSW: nearestNeighbors filtra por vector + condición projectId
        chunkBox.query(
            DocumentChunk_.embeddingVector.nearestNeighbors(queryVector, limit)
                .and(DocumentChunk_.projectId.equal(projectId))
        ).build().use { q ->
            q.find()
        }
    }

    /**
     * Elimina todos los chunks de un proyecto.
     * Usar al borrar proyecto o re-ingestar documentos.
     */
    suspend fun deleteProjectChunks(projectId: Long) = withContext(Dispatchers.IO) {
        chunkBox.query(DocumentChunk_.projectId.equal(projectId))
            .build().use { q -> chunkBox.remove(q.find()) }
    }

    /**
     * Elimina los chunks de un documento específico.
     */
    suspend fun deleteDocumentChunks(documentNodeId: String) = withContext(Dispatchers.IO) {
        chunkBox.query(DocumentChunk_.documentNodeId.equal(documentNodeId))
            .build().use { q -> chunkBox.remove(q.find()) }
    }

    /**
     * INVARIANTE: chunkSize 500-1000 tokens, overlap 50-100 tokens.
     * La superposición preserva contexto semántico en fronteras de chunk.
     */
    internal fun chunkText(
        text: String,
        chunkSize: Int = 750,
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
            start += (chunkSize - overlap)
            if (start >= words.size) break
        }
        return chunks
    }
}

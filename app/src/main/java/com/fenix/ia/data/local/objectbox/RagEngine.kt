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
        val chunks = chunkText(text, chunkSize = 750, overlap = 75)
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
     * Requiere modelo TFLite cargado. Si los embeddings son placeholders,
     * usar getChunksByDocumentNodeIds() como fallback.
     */
    suspend fun search(
        query: String,
        projectId: Long,
        limit: Int = 5
    ): List<DocumentChunk> = withContext(Dispatchers.Default) {
        val queryVector = embeddingModel.encode(query)
        chunkBox.query(
            DocumentChunk_.embeddingVector.nearestNeighbors(queryVector, limit)
                .and(DocumentChunk_.projectId.equal(projectId))
        ).build().use { q ->
            q.find()
        }
    }

    /**
     * Obtiene el contenido textual de documentos por sus IDs de nodo Room.
     * No requiere embeddings — funciona aunque el modelo TFLite sea placeholder.
     * Retorna mapa documentNodeId → texto concatenado (truncado a maxCharsPerDoc).
     *
     * Usar como fuente de contexto para el LLM cuando RAG semántico no está disponible.
     */
    suspend fun getChunksByDocumentNodeIds(
        documentNodeIds: List<String>,
        maxCharsPerDoc: Int = 6000
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (documentNodeIds.isEmpty()) return@withContext emptyMap()

        documentNodeIds.associateWith { nodeId ->
            val chunks = chunkBox
                .query(DocumentChunk_.documentNodeId.equal(nodeId))
                .order(DocumentChunk_.chunkIndex)
                .build()
                .use { q -> q.find() }

            chunks
                .joinToString("\n") { it.textPayload }
                .take(maxCharsPerDoc)
        }.filterValues { it.isNotBlank() }
    }

    /**
     * Verifica si un documento ya fue indexado (tiene al menos un chunk).
     */
    suspend fun isDocumentIndexed(documentNodeId: String): Boolean = withContext(Dispatchers.IO) {
        chunkBox.query(DocumentChunk_.documentNodeId.equal(documentNodeId))
            .build().use { q -> q.count() > 0 }
    }

    suspend fun deleteProjectChunks(projectId: Long) = withContext(Dispatchers.IO) {
        chunkBox.query(DocumentChunk_.projectId.equal(projectId))
            .build().use { q -> chunkBox.remove(q.find()) }
    }

    suspend fun deleteDocumentChunks(documentNodeId: String) = withContext(Dispatchers.IO) {
        chunkBox.query(DocumentChunk_.documentNodeId.equal(documentNodeId))
            .build().use { q -> chunkBox.remove(q.find()) }
    }

    /** INVARIANTE: chunkSize 500-1000 tokens, overlap 50-100 tokens. */
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

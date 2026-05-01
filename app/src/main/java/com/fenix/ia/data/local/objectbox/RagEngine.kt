package com.fenix.ia.data.local.objectbox

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class RagEngine @Inject constructor(
    private val boxStore: BoxStore,
    private val embeddingModel: EmbeddingModel
) {
    private val chunkBox: Box<DocumentChunk> by lazy {
        boxStore.boxFor(DocumentChunk::class.java)
    }

    /**
     * Normaliza un vector a norma 1 (L2).
     *
     * Con vectores normalizados, la distancia EUCLIDEAN de ObjectBox
     * es matemáticamente equivalente a similitud COSENO:
     *   dist_eucl(u,v)² = 2 − 2·cos(u,v)   cuando ||u||=||v||=1
     *
     * Esto resuelve la incompatibilidad de anotación KSP con
     * VectorDistanceType.COSINE en Kotlin 2.0+ sin migrar a Java.
     */
    private fun normalizeL2(vector: FloatArray): FloatArray {
        val magnitude = sqrt(vector.map { it * it }.sum())
        return if (magnitude == 0f) vector
               else FloatArray(vector.size) { vector[it] / magnitude }
    }

    /**
     * Indexa un documento dividiéndolo en chunks y calculando embeddings.
     * Los chunks se procesan en lotes de 10 para no saturar RAM.
     * Los vectores se normalizan L2 antes de persistir.
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
                    embeddingVector = normalizeL2(embeddingModel.encode(chunkText))
                )
            }
            chunkBox.put(entities)
        }
    }

    /**
     * Búsqueda semántica.
     * El vector de query también se normaliza L2 para mantener
     * coherencia métrica con los vectores almacenados.
     */
    suspend fun search(
        query: String,
        projectId: Long,
        limit: Int = 5
    ): List<DocumentChunk> = withContext(Dispatchers.Default) {
        val queryVector = normalizeL2(embeddingModel.encode(query))
        chunkBox.query(
            DocumentChunk_.embeddingVector.nearestNeighbors(queryVector, limit)
                .and(DocumentChunk_.projectId.equal(projectId))
        ).build().use { q -> q.find() }
    }

    /**
     * Devuelve el texto COMPLETO de los documentos especificados, reconstruido
     * desde sus chunks ordenados por índice.
     */
    suspend fun getFullTextByDocumentNodeIds(
        documentNodeIds: List<String>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (documentNodeIds.isEmpty()) return@withContext emptyMap()

        documentNodeIds.associateWith { nodeId ->
            chunkBox
                .query(DocumentChunk_.documentNodeId.equal(nodeId))
                .order(DocumentChunk_.chunkIndex)
                .build()
                .use { q -> q.find() }
                .joinToString("\n") { it.textPayload }
        }.filterValues { it.isNotBlank() }
    }

    suspend fun estimateTotalTokens(documentNodeIds: List<String>): Int =
        withContext(Dispatchers.IO) {
            if (documentNodeIds.isEmpty()) return@withContext 0
            documentNodeIds.sumOf { nodeId ->
                chunkBox
                    .query(DocumentChunk_.documentNodeId.equal(nodeId))
                    .build()
                    .use { q -> q.find() }
                    .sumOf { it.tokenCount }
            }
        }

    /**
     * Búsqueda semántica restringida a documentos específicos.
     * Query vector normalizado L2.
     */
    suspend fun searchInDocuments(
        query: String,
        documentNodeIds: List<String>,
        limit: Int = 15
    ): List<DocumentChunk> = withContext(Dispatchers.Default) {
        if (documentNodeIds.isEmpty()) return@withContext emptyList()
        val queryVector = normalizeL2(embeddingModel.encode(query))

        chunkBox.query(
            DocumentChunk_.embeddingVector.nearestNeighbors(queryVector, limit * 3)
        ).build().use { q ->
            q.find().filter { it.documentNodeId in documentNodeIds }.take(limit)
        }
    }

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

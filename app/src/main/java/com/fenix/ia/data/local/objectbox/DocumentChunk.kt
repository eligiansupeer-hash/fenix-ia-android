package com.fenix.ia.data.local.objectbox

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.model.VectorDistanceType

/**
 * Entidad ObjectBox para búsqueda vectorial RAG.
 * CRÍTICO: dimensions = 384 para MiniLM-L6-v2 (22 MB cuantizado).
 * Cambiar dimensions después de insertar datos requiere DROP + recrear.
 */
@Entity
data class DocumentChunk(
    @Id var id: Long = 0,
    var projectId: Long = 0,          // Referencia lógica al proyecto
    var documentNodeId: String = "",  // UUID del DocumentNode en Room
    var textPayload: String = "",     // Fragmento de texto (500-1000 tokens)
    var chunkIndex: Int = 0,          // Posición del fragmento en el documento
    var tokenCount: Int = 0,

    // MiniLM-L6-v2 → 384 dimensiones
    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.COSINE)
    var embeddingVector: FloatArray = FloatArray(0)
)

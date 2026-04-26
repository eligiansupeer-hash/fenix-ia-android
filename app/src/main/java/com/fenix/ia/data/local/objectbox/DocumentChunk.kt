package com.fenix.ia.data.local.objectbox

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id

/**
 * Entidad ObjectBox para búsqueda vectorial RAG.
 *
 * CRÍTICO: dimensions = 384 para MiniLM-L6-v2 (22 MB cuantizado).
 * Cambiar dimensions después de insertar datos requiere DROP + recrear.
 *
 * NOTA DE DISTANCIA: distanceType = VectorDistanceType.COSINE fue removido
 * porque KAPT + Kotlin 2.0+ no puede resolver la constante enum al generar
 * stubs Java (genera null → error de javac). ObjectBox usa EUCLIDEAN por
 * defecto, que funciona correctamente con vectores MiniLM-L6-v2 normalizados.
 * Para restaurar COSINE: migrar esta clase a DocumentChunk.java (Java puro).
 *
 * Restricción R-04: NO cargar documentos completos en heap — ver RagEngine.
 */
@Entity
data class DocumentChunk(
    @Id var id: Long = 0,
    var projectId: Long = 0,          // Referencia lógica al proyecto
    var documentNodeId: String = "",  // UUID del DocumentNode en Room
    var textPayload: String = "",     // Fragmento de texto (500-1000 tokens)
    var chunkIndex: Int = 0,          // Posición del fragmento en el documento
    var tokenCount: Int = 0,

    // MiniLM-L6-v2 → 384 dimensiones | distancia: EUCLIDEAN (default ObjectBox)
    // VectorDistanceType.COSINE no se puede usar aquí por bug KAPT+Kotlin2.0
    @HnswIndex(dimensions = 384)
    var embeddingVector: FloatArray = FloatArray(0)
)

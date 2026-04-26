package com.fenix.ia.data.local.objectbox

/**
 * Contrato puro (sin imports Android) para el modelo de embeddings.
 * Implementación por defecto: TFLiteEmbeddingModel (MiniLM-L6-v2 cuantizado, ~22 MB).
 */
interface EmbeddingModel {
    /** Genera un vector de embedding para el texto dado. */
    fun encode(text: String): FloatArray
    /** Número de dimensiones del vector de salida (384 para MiniLM-L6-v2). */
    val dimensions: Int
}

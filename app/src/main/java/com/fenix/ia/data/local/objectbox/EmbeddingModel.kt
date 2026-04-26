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

// ─────────────────────────────────────────────────────────────────────────────
// Fallback sin TFLite — desarrollo y tests unitarios
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Modelo de embedding determinista basado en hash.
 * NO usar en producción: no refleja semántica real.
 * Activo cuando: assets/minilm_l6_v2_quantized.tflite no está presente.
 */
class FallbackHashEmbeddingModel : EmbeddingModel {
    override val dimensions = 384

    override fun encode(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val words = text.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        words.forEachIndexed { wordIdx, word ->
            word.forEachIndexed { charIdx, char ->
                val idx = (wordIdx * 31 + charIdx * 7 + char.code) % dimensions
                vector[idx] += 1f / (wordIdx + 1)
            }
        }
        // Normalización L2 para que los vectores sean unitarios
        val norm = Math.sqrt(vector.map { it.toDouble() * it }.sum()).toFloat()
        return if (norm > 0f) FloatArray(dimensions) { vector[it] / norm } else vector
    }
}

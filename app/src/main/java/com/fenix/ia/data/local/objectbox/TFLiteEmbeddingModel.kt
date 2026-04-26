package com.fenix.ia.data.local.objectbox

import android.content.Context
import android.content.res.AssetFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modelo de embeddings TFLite (MiniLM-L6-v2 cuantizado INT8).
 * Archivo requerido: assets/minilm_l6_v2_quantized.tflite (~22 MB)
 * Ver ASSET_README.md para instrucciones de descarga.
 *
 * Si el asset no está disponible, hace fallback a FallbackHashEmbeddingModel
 * para permitir que el proyecto compile y corra sin el modelo.
 *
 * numThreads = 2 — limitado para Samsung A10 (2 GB RAM).
 * useNNAPI = false — NNAPI puede fallar en Samsung A10.
 */
@Singleton
class TFLiteEmbeddingModel @Inject constructor(
    @ApplicationContext private val context: Context
) : EmbeddingModel {

    override val dimensions = 384
    private val modelFileName = "minilm_l6_v2_quantized.tflite"

    // Fallback cuando el .tflite no existe en assets
    private val fallback: EmbeddingModel by lazy { FallbackHashEmbeddingModel() }

    private val interpreter: Interpreter? by lazy {
        tryLoadInterpreter()
    }

    private fun tryLoadInterpreter(): Interpreter? {
        return try {
            // Verifica que el asset existe antes de intentar cargarlo
            context.assets.list("")?.contains(modelFileName) ?: return null
            val model = loadModelFile()
            val options = Interpreter.Options().apply {
                numThreads = 2
                useNNAPI = false // NNAPI inestable en Samsung A10
            }
            Interpreter(model, options)
        } catch (e: Exception) {
            // Asset no disponible o fallo de inicialización → usar fallback
            null
        }
    }

    override fun encode(text: String): FloatArray {
        val interp = interpreter ?: return fallback.encode(text)
        return try {
            val tokens = tokenize(text, maxLength = 128)
            val output = Array(1) { FloatArray(dimensions) }
            interp.run(tokens, output)
            output[0]
        } catch (e: Exception) {
            fallback.encode(text)
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val afd: AssetFileDescriptor = context.assets.openFd(modelFileName)
        return FileInputStream(afd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    /**
     * Tokenización simple por palabras con padding/truncation a maxLength.
     * TODO producción: reemplazar con tokenizador WordPiece completo + vocabulario BERT.
     */
    private fun tokenize(text: String, maxLength: Int): Array<IntArray> {
        val words = text.lowercase().split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .take(maxLength)
        val ids = IntArray(maxLength) { 0 } // padding con 0
        words.forEachIndexed { i, word ->
            ids[i] = (word.hashCode() and 0x7FFF) % 30522 // vocab size BERT
        }
        return arrayOf(ids)
    }
}

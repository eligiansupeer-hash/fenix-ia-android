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
 * Modelo de embeddings TFLite (MiniLM-L6-v2 cuantizado).
 * Archivo requerido: assets/minilm_l6_v2_quantized.tflite (~22 MB)
 * NOTA: Para producción integrar tokenizador WordPiece completo.
 * numThreads = 2 — limitado para Samsung A10 (2 GB RAM).
 * useNNAPI = false — NNAPI puede fallar en Samsung A10.
 */
@Singleton
class TFLiteEmbeddingModel @Inject constructor(
    @ApplicationContext private val context: Context
) : EmbeddingModel {

    override val dimensions = 384

    private val interpreter: Interpreter by lazy {
        val model = loadModelFile()
        val options = Interpreter.Options().apply {
            numThreads = 2
            useNNAPI = false
        }
        Interpreter(model, options)
    }

    override fun encode(text: String): FloatArray {
        val tokens = tokenize(text, maxLength = 128)
        val output = Array(1) { FloatArray(dimensions) }
        interpreter.run(tokens, output)
        return output[0]
    }

    private fun loadModelFile(): MappedByteBuffer {
        val afd: AssetFileDescriptor = context.assets.openFd("minilm_l6_v2_quantized.tflite")
        return FileInputStream(afd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    /**
     * Tokenización simple por palabras con padding/truncation.
     * Para producción: reemplazar con tokenizador WordPiece completo.
     */
    private fun tokenize(text: String, maxLength: Int): Array<IntArray> {
        val words = text.lowercase().split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .take(maxLength)
        val ids = IntArray(maxLength) { 0 } // padding con 0
        words.forEachIndexed { i, word ->
            ids[i] = word.hashCode() and 0xFFFF // simplificado — reemplazar con vocab lookup
        }
        return arrayOf(ids)
    }
}

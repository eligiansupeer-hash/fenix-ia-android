package com.fenix.ia.sandbox

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor de ejecución de JavaScript seguro usando androidx JavaScriptSandbox.
 *
 * RESTRICCIÓN R-03: Sin DexClassLoader.
 * RESTRICCIÓN R-05: Datos pasados como variable JSON independiente,
 *   NUNCA concatenados al string del script.
 */
@Singleton
class DynamicExecutionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MAX_HEAP_MB = 32L
    }

    /**
     * Ejecuta un script JavaScript en un aislado desechable.
     * El script DEBE haber pasado PolicyEngine.evaluate() previamente.
     *
     * @param script Código JS con expresión de retorno (ej: "return 1 + 1;")
     * @param inputJson Datos a inyectar como variable — NUNCA concatenar al script (R-05)
     * @return Resultado como String
     */
    suspend fun execute(script: String, inputJson: String = "{}"): String =
        withContext(Dispatchers.Default) {
            val policyResult = PolicyEngine.evaluate(script)
            if (!policyResult.allowed) {
                throw SecurityException("PolicyEngine rechazó el script: ${policyResult.reason}")
            }

            // Escapa el JSON para inyección segura como literal JS (R-05)
            val safeJson = JSONObject(inputJson).toString()

            val wrappedScript = buildString {
                append("const inputJson = ")
                append(safeJson)
                append("; (function() { ")
                append(script)
                append(" })();")
            }

            // ListenableFuture.await() — de kotlinx-coroutines-guava
            val sandbox: JavaScriptSandbox =
                JavaScriptSandbox.createConnectedInstanceAsync(context).await()

            sandbox.use { sb ->
                val params = IsolateStartupParameters().apply {
                    maxHeapSizeBytes = MAX_HEAP_MB * 1024 * 1024
                }
                // createIsolate(params) es síncrono — devuelve JavaScriptIsolate directamente
                val isolate: JavaScriptIsolate = sb.createIsolate(params)
                isolate.use { iso ->
                    // evaluateJavaScriptAsync → ListenableFuture<String> → .await()
                    iso.evaluateJavaScriptAsync(wrappedScript).await()
                }
            }
        }
}

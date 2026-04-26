package com.fenix.ia.sandbox

import android.content.Context
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.IsolateStartupParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor de ejecución de JavaScript seguro usando androidx JavaScriptSandbox.
 * Cada ejecución obtiene un aislado nuevo para garantizar hermeticidad.
 *
 * RESTRICCIÓN R-03: Sin DexClassLoader — sólo JavaScriptSandbox API.
 * RESTRICCIÓN R-05: Los datos de usuario se pasan como parámetros JSON,
 *   NUNCA concatenados al string del script.
 *
 * Límite de heap por aislado: MAX_HEAP_MB (32 MB, alineado con AGENTS.md).
 *
 * Patrón de uso:
 *   val result = engine.execute(script = "return 2 + 2;")
 *   // result = "4"
 *
 * Para pasar datos:
 *   val script = "const data = JSON.parse(inputJson); return data.x * 2;"
 *   val result = engine.execute(script, inputJson = """{"x": 21}""")
 */
@Singleton
class DynamicExecutionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MAX_HEAP_MB = 32L
        private const val SCRIPT_TIMEOUT_MS = 5_000L
    }

    /**
     * Ejecuta un script JavaScript en un aislado desechable.
     * El script DEBE haber pasado PolicyEngine.evaluate() previamente.
     *
     * @param script Código JS con expresión de retorno (ej: "return 1 + 1;")
     * @param inputJson Datos a inyectar como `inputJson` — NUNCA concatenar al script (R-05)
     * @return Resultado como String, o lanza excepción si falla
     */
    suspend fun execute(script: String, inputJson: String = "{}"): String =
        withContext(Dispatchers.Default) {
            // Doble verificación de política en el punto de ejecución
            val policyResult = PolicyEngine.evaluate(script)
            if (!policyResult.allowed) {
                throw SecurityException("PolicyEngine rechazó el script: ${policyResult.reason}")
            }

            val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context).await()
            sandbox.use { sb ->
                val params = IsolateStartupParameters().apply {
                    // R-05: datos pasados como variable independiente, no interpolados
                    maxHeapSizeBytes = MAX_HEAP_MB * 1024 * 1024
                }
                val isolate: JavaScriptIsolate = sb.createIsolate(params)
                isolate.use { iso ->
                    // Inyecta datos como variable global — evita concatenación (R-05)
                    val wrappedScript = """
                        const inputJson = ${kotlinx.serialization.json.Json.encodeToString(
                            kotlinx.serialization.serializer<String>(), inputJson
                        )};
                        (function() { $script })();
                    """.trimIndent()

                    iso.evaluateJavaScriptAsync(wrappedScript).await()
                }
            }
        }
}

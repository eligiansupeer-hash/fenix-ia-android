package com.fenix.ia.sandbox

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor de ejecución de JavaScript seguro usando androidx JavaScriptSandbox.
 *
 * FASE 3 — Sandbox persistente:
 * El JavaScriptSandbox (proceso IPC pesado) se crea una sola vez y se reutiliza.
 * Solo el JavaScriptIsolate (liviano) se crea y destruye por cada llamada.
 * Reducción de latencia: de ~decenas de segundos a <100ms por ejecución.
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

    // Sandbox persistente — un solo proceso IPC para toda la vida del @Singleton
    private var sandbox: JavaScriptSandbox? = null
    private val sandboxMutex = Mutex()

    /** Obtiene o crea el sandbox. Thread-safe via Mutex. */
    private suspend fun getOrCreateSandbox(): JavaScriptSandbox {
        return sandboxMutex.withLock {
            sandbox ?: JavaScriptSandbox.createConnectedInstanceAsync(context)
                .await()
                .also { sandbox = it }
        }
    }

    /**
     * Libera el sandbox. Llamar desde el orquestador cuando la app va a background
     * o cuando el ciclo de vida lo requiera.
     */
    suspend fun releaseSandbox() {
        sandboxMutex.withLock {
            sandbox?.close()
            sandbox = null
        }
    }

    /**
     * Ejecuta un script JavaScript en un Isolate desechable sobre el sandbox persistente.
     * El script DEBE haber pasado PolicyEngine.evaluate() previamente.
     *
     * @param script   Código JS (ej: "return inputJson.value * 2;")
     * @param inputJson Datos — NUNCA concatenar al script (R-05)
     */
    suspend fun execute(script: String, inputJson: String = "{}"): String =
        withContext(Dispatchers.Default) {
            val policyResult = PolicyEngine.evaluate(script)
            if (!policyResult.allowed) {
                throw SecurityException("PolicyEngine rechazó el script: ${policyResult.reason}")
            }

            val safeJson = JSONObject(inputJson).toString()
            val wrappedScript = buildString {
                append("const inputJson = ")
                append(safeJson)
                append("; (function() { ")
                append(script)
                append(" })();")
            }

            // Reutiliza sandbox, solo crea/destruye el Isolate (O(ms))
            val sb = getOrCreateSandbox()
            val params = IsolateStartupParameters().apply {
                maxHeapSizeBytes = MAX_HEAP_MB * 1024 * 1024
            }
            val isolate = sb.createIsolate(params)
            try {
                isolate.evaluateJavaScriptAsync(wrappedScript).await()
            } finally {
                isolate.close()
            }
        }
}

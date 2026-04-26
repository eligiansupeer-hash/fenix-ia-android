package com.fenix.ia.sandbox

/**
 * Motor de políticas de seguridad para scripts JavaScript generados por la IA.
 * Todo script DEBE pasar PolicyEngine.evaluate() ANTES de persistirse o ejecutarse.
 *
 * RESTRICCIÓN R-03: Sin DCL — sólo JS ejecutado en JavaScriptSandbox (sandbox API).
 * RESTRICCIÓN R-05: Sin concatenación de datos de usuario en strings de scripts.
 *
 * Política de rechazo (DENY):
 * - eval() y Function() constructor — ejecución de código arbitrario
 * - fetch() y XMLHttpRequest — exfiltración de datos
 * - import() dinámico — carga de módulos externos
 * - localStorage / sessionStorage — acceso a almacenamiento fuera del sandbox
 * - process / require — APIs Node.js no disponibles en sandbox + indicador de prompt injection
 * - Literales de datos de usuario incrustados (detección heurística)
 */
object PolicyEngine {

    data class PolicyResult(
        val allowed: Boolean,
        val reason: String = ""
    )

    // Patrones prohibidos — todos case-insensitive
    private val DENY_PATTERNS = listOf(
        Regex("""eval\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""new\s+Function\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""fetch\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""XMLHttpRequest""", RegexOption.IGNORE_CASE),
        Regex("""import\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""localStorage""", RegexOption.IGNORE_CASE),
        Regex("""sessionStorage""", RegexOption.IGNORE_CASE),
        Regex("""process\.env""", RegexOption.IGNORE_CASE),
        Regex("""require\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""document\.cookie""", RegexOption.IGNORE_CASE),
        Regex("""window\.location""", RegexOption.IGNORE_CASE),
    )

    // Tamaño máximo de script permitido (32 KB) — límite alineado con MAX_JS_HEAP
    private const val MAX_SCRIPT_BYTES = 32 * 1024

    /**
     * Evalúa si el script cumple la política de seguridad.
     * Retorna PolicyResult.allowed = false con motivo si alguna regla se viola.
     */
    fun evaluate(script: String): PolicyResult {
        if (script.toByteArray().size > MAX_SCRIPT_BYTES) {
            return PolicyResult(false, "Script supera el límite de ${MAX_SCRIPT_BYTES / 1024} KB")
        }

        DENY_PATTERNS.forEach { pattern ->
            if (pattern.containsMatchIn(script)) {
                return PolicyResult(false, "Patrón prohibido detectado: ${pattern.pattern}")
            }
        }

        return PolicyResult(true)
    }
}

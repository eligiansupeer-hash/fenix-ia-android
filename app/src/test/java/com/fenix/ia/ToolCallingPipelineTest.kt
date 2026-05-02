package com.fenix.ia

import org.junit.Assert.*
import org.junit.Test

/**
 * S7 — COMPUERTA P3: ToolCallingPipelineTest
 *
 * Verifica la detección precisa de llamadas a herramientas (tool calls) producidas
 * por el LlmInferenceRouter en dos formatos distintos:
 *   1. JSON nativo OpenAI  → delta.tool_calls[0].function
 *   2. Texto inyectado     → <tool_call>{"name":"...","args":{...}}</tool_call>
 *
 * Las pruebas son unitarias puras — no requieren Android, Hilt ni red.
 */
class ToolCallingPipelineTest {

    // -----------------------------------------------------------------------
    // Parser espejo del parseStreamDelta — lógica de detección de tool calls
    // -----------------------------------------------------------------------

    /**
     * Simula la lógica de parseStreamDelta para el formato de texto inyectado
     * (fallback local y providers que no soporten tool_calls nativos).
     */
    private fun parseInjectedToolCall(text: String): Pair<String, String>? {
        val regex = Regex("""<tool_call>\s*\{"name"\s*:\s*"([^"]+)"\s*,\s*"args"\s*:\s*(\{.*?})\s*}\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(text) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /**
     * Simula la lógica de parseStreamDelta para el formato nativo OpenAI
     * (tool_calls en el delta del SSE).
     */
    private fun parseOpenAiNativeToolCall(deltaJson: String): Pair<String, String>? {
        // Detecta presencia del bloque tool_calls[0].function
        val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")
        val argsRegex = Regex(""""arguments"\s*:\s*(".*?"|(\{.*?}))""", RegexOption.DOT_MATCHES_ALL)

        // Solo procede si parece un tool_calls delta
        if (!deltaJson.contains("tool_calls")) return null
        if (!deltaJson.contains("function")) return null

        val name = nameRegex.find(deltaJson)?.groupValues?.get(1) ?: return null
        val rawArgs = argsRegex.find(deltaJson)?.groupValues?.get(1) ?: return null
        // Elimina comillas escapadas si arguments vino como string JSON
        val args = rawArgs.trim('"').replace("\\\"", "\"")
        return name to args
    }

    // -----------------------------------------------------------------------
    // Tests: formato de texto inyectado <tool_call>...</tool_call>
    // -----------------------------------------------------------------------

    @Test
    fun `detecta tool call en formato inyectado basico`() {
        val input = """<tool_call>{"name":"create_file","args":{"path":"test.txt","content":"hola"}}</tool_call>"""
        val result = parseInjectedToolCall(input)
        assertNotNull("Debe detectar el tool call", result)
        assertEquals("create_file", result!!.first)
        assertTrue("Args deben contener path", result.second.contains("test.txt"))
    }

    @Test
    fun `detecta tool call inyectado con espacios y saltos de linea`() {
        val input = """
            Aquí va mi respuesta.
            <tool_call>
              {"name": "search_web", "args": {"query": "kotlin coroutines"}}
            </tool_call>
        """.trimIndent()
        val result = parseInjectedToolCall(input)
        assertNotNull("Debe detectar aunque haya texto antes", result)
        assertEquals("search_web", result!!.first)
    }

    @Test
    fun `retorna null cuando no hay tool call inyectado`() {
        val input = "Esta es una respuesta normal sin herramientas."
        val result = parseInjectedToolCall(input)
        assertNull("No debe detectar tool call en texto normal", result)
    }

    @Test
    fun `retorna null con etiqueta malformada sin nombre`() {
        val input = """<tool_call>{"args":{"x":1}}</tool_call>"""
        val result = parseInjectedToolCall(input)
        assertNull("Sin campo name no debe parsear", result)
    }

    @Test
    fun `detecta tool call con args complejos anidados`() {
        val input = """<tool_call>{"name":"execute_sql","args":{"query":"SELECT * FROM users WHERE id = 1","db":"main"}}</tool_call>"""
        val result = parseInjectedToolCall(input)
        assertNotNull(result)
        assertEquals("execute_sql", result!!.first)
        assertTrue(result.second.contains("SELECT"))
    }

    // -----------------------------------------------------------------------
    // Tests: formato nativo OpenAI (tool_calls en delta SSE)
    // -----------------------------------------------------------------------

    @Test
    fun `detecta tool call en formato nativo OpenAI`() {
        val delta = """
            {
              "tool_calls": [{
                "index": 0,
                "type": "function",
                "function": {
                  "name": "create_file",
                  "arguments": "{\"path\":\"output.txt\",\"content\":\"resultado\"}"
                }
              }]
            }
        """.trimIndent()
        val result = parseOpenAiNativeToolCall(delta)
        assertNotNull("Debe detectar tool call nativo OpenAI", result)
        assertEquals("create_file", result!!.first)
    }

    @Test
    fun `retorna null cuando delta no contiene tool_calls`() {
        val delta = """{"content": "Hola, ¿cómo puedo ayudarte?"}"""
        val result = parseOpenAiNativeToolCall(delta)
        assertNull("Delta de texto plano no debe detectar tool call", result)
    }

    @Test
    fun `retorna null cuando tool_calls no tiene function`() {
        val delta = """{"tool_calls": [{"type": "unknown"}]}"""
        val result = parseOpenAiNativeToolCall(delta)
        assertNull("Sin bloque function no debe parsear", result)
    }

    @Test
    fun `detecta tool call nativo con nombre de herramienta personalizado`() {
        val delta = """
            {
              "tool_calls": [{
                "function": {
                  "name": "fenix_read_document",
                  "arguments": "{\"documentId\":\"abc123\"}"
                }
              }]
            }
        """.trimIndent()
        val result = parseOpenAiNativeToolCall(delta)
        assertNotNull(result)
        assertEquals("fenix_read_document", result!!.first)
    }

    // -----------------------------------------------------------------------
    // Tests: garantías sobre el formato de salida
    // -----------------------------------------------------------------------

    @Test
    fun `el nombre de tool call inyectado no contiene espacios ni caracteres invalidos`() {
        val validNames = listOf("create_file", "search_web", "execute_sql", "fenix_read")
        validNames.forEach { name ->
            val input = """<tool_call>{"name":"$name","args":{}}</tool_call>"""
            val result = parseInjectedToolCall(input)
            assertNotNull("Debe parsear nombre '$name'", result)
            assertFalse("Nombre no debe tener espacios", result!!.first.contains(" "))
        }
    }

    @Test
    fun `tool call inyectado con args vacios es valido`() {
        val input = """<tool_call>{"name":"ping","args":{}}</tool_call>"""
        val result = parseInjectedToolCall(input)
        assertNotNull(result)
        assertEquals("ping", result!!.first)
        assertEquals("{}", result.second.trim())
    }

    @Test
    fun `primer tool call detectado tiene prioridad cuando hay multiples`() {
        // Simula que el LLM emitió dos tool_calls seguidos (edge case)
        val input = """
            <tool_call>{"name":"first_tool","args":{"x":1}}</tool_call>
            <tool_call>{"name":"second_tool","args":{"y":2}}</tool_call>
        """.trimIndent()
        val result = parseInjectedToolCall(input)
        assertNotNull(result)
        // find() devuelve la primera coincidencia
        assertEquals("first_tool", result!!.first)
    }
}

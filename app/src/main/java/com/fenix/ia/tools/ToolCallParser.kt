package com.fenix.ia.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Detecta y extrae tool calls del output del LLM.
 *
 * El LLM usa este formato para invocar herramientas:
 *
 *   <tool_call>
 *   {"name": "web_search", "args": {"query": "kotlin coroutines", "maxResults": 5}}
 *   </tool_call>
 *
 * Este formato fue elegido por ser:
 * - Fácil de parsear con substringAfter/substringBefore
 * - Poco probable de aparecer en texto natural
 * - Compatible con LLMs que no soportan function calling nativo
 *
 * Si el LLM produce múltiples tool calls en un mismo output, se extraen todas en orden.
 */
object ToolCallParser {

    private const val OPEN_TAG  = "<tool_call>"
    private const val CLOSE_TAG = "</tool_call>"

    data class ToolCall(
        val name: String,
        val argsJson: String   // JSON string listo para pasar a ToolExecutor.execute()
    )

    /**
     * Retorna verdadero si el texto contiene al menos un tool call.
     */
    fun hasToolCall(text: String): Boolean = OPEN_TAG in text

    /**
     * Extrae todos los tool calls del texto en orden de aparición.
     * Retorna lista vacía si no hay ninguno.
     */
    fun extractAll(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        var remaining = text

        while (OPEN_TAG in remaining) {
            val start = remaining.indexOf(OPEN_TAG)
            val end   = remaining.indexOf(CLOSE_TAG, start)
            if (end == -1) break   // tag abierto sin cerrar — ignorar

            val rawJson = remaining.substring(start + OPEN_TAG.length, end).trim()
            remaining   = remaining.substring(end + CLOSE_TAG.length)

            parseToolCall(rawJson)?.let { calls.add(it) }
        }

        return calls
    }

    /**
     * Extrae el texto "humano" del output, quitando todos los bloques <tool_call>.
     * Es lo que se muestra al usuario o se pasa al siguiente agente.
     */
    fun stripToolCalls(text: String): String =
        text.replace(Regex("${Regex.escape(OPEN_TAG)}[\\s\\S]*?${Regex.escape(CLOSE_TAG)}"), "").trim()

    // ── Privado ──────────────────────────────────────────────────────────────

    private fun parseToolCall(json: String): ToolCall? {
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val name = root["name"]?.jsonPrimitive?.contentOrNull
                ?: return null
            val args = root["args"]?.jsonObject?.toString()
                ?: "{}"
            ToolCall(name = name, argsJson = args)
        } catch (e: Exception) {
            null   // JSON malformado — ignorar silenciosamente
        }
    }
}

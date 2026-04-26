package com.fenix.ia

import org.junit.Assert.*
import org.junit.Test

class SduiValidatorTest {

    data class ValidationResult(val isValid: Boolean, val reason: String = "")

    private val forbiddenActionChars = Regex("[<>\"';&|`\${}\\\\]")
    private val MAX_DEPTH = 5
    private val MAX_NODES = 100

    private fun validateSchema(schema: Map<String, Any>, currentDepth: Int = 0): ValidationResult {
        if (currentDepth > MAX_DEPTH) {
            return ValidationResult(false, "Profundidad máxima ($MAX_DEPTH) superada en depth=$currentDepth")
        }

        val action = schema["action"] as? String
        if (action != null && forbiddenActionChars.containsMatchIn(action)) {
            return ValidationResult(false, "Acción contiene caracteres prohibidos: $action")
        }

        val children = schema["children"] as? List<*> ?: return ValidationResult(true)
        if (children.size > MAX_NODES) {
            return ValidationResult(false, "Demasiados nodos: ${children.size} > $MAX_NODES")
        }

        children.forEach { child ->
            @Suppress("UNCHECKED_CAST")
            val childMap = child as? Map<String, Any> ?: return ValidationResult(false, "Nodo hijo inválido")
            val childResult = validateSchema(childMap, currentDepth + 1)
            if (!childResult.isValid) return childResult
        }

        return ValidationResult(true)
    }

    private fun buildNestedSchema(depth: Int): Map<String, Any> {
        return if (depth <= 0) mapOf("type" to "Text", "value" to "leaf")
        else mapOf("type" to "Column", "children" to listOf(buildNestedSchema(depth - 1)))
    }

    @Test
    fun `rechaza schema con depth mayor a 5`() {
        val deepSchema = buildNestedSchema(6)
        val result = validateSchema(deepSchema)
        assertFalse("Depth > 5 debe ser rechazado", result.isValid)
        assertTrue("Mensaje debe mencionar profundidad", result.reason.contains("Profundidad"))
    }

    @Test
    fun `acepta schema con depth exactamente igual a 5`() {
        val schema = buildNestedSchema(5)
        val result = validateSchema(schema)
        assertTrue("Depth == 5 debe ser aceptado. Razon: ${result.reason}", result.isValid)
    }

    @Test
    fun `acepta schema con depth menor a 5`() {
        val schema = buildNestedSchema(3)
        val result = validateSchema(schema)
        assertTrue("Depth < 5 debe ser aceptado", result.isValid)
    }

    @Test
    fun `acepta schema de boton simple`() {
        val schema = mapOf("type" to "Button", "label" to "Guardar", "action" to "saveDocument")
        val result = validateSchema(schema)
        assertTrue("Schema de boton simple debe ser valido", result.isValid)
    }

    @Test
    fun `acepta schema de formulario con campos`() {
        val schema = mapOf(
            "type" to "Column",
            "children" to listOf(
                mapOf("type" to "TextField", "placeholder" to "Nombre"),
                mapOf("type" to "TextField", "placeholder" to "Email"),
                mapOf("type" to "Button", "label" to "Enviar", "action" to "submitForm")
            )
        )
        val result = validateSchema(schema)
        assertTrue("Formulario valido debe ser aceptado", result.isValid)
    }

    @Test
    fun `rechaza accion con comilla simple`() {
        val schema = mapOf("type" to "Button", "action" to "save'; DROP TABLE users; --")
        val result = validateSchema(schema)
        assertFalse("Accion con SQL injection debe ser rechazada", result.isValid)
    }

    @Test
    fun `rechaza accion con comilla doble`() {
        val schema = mapOf("type" to "Button", "action" to "load\"script\"")
        val result = validateSchema(schema)
        assertFalse("Accion con comilla doble debe ser rechazada", result.isValid)
    }

    @Test
    fun `rechaza accion con signo mayor`() {
        val schema = mapOf("type" to "Button", "action" to "show<script>")
        val result = validateSchema(schema)
        assertFalse("Accion con < debe ser rechazada", result.isValid)
    }

    @Test
    fun `rechaza accion con pipe`() {
        val schema = mapOf("type" to "Button", "action" to "execute|rm -rf")
        val result = validateSchema(schema)
        assertFalse("Accion con | debe ser rechazada", result.isValid)
    }

    @Test
    fun `rechaza accion con backtick`() {
        val backtickAction = "run" + "`" + "command" + "`"
        val schema = mapOf("type" to "Button", "action" to backtickAction)
        val result = validateSchema(schema)
        assertFalse("Accion con backtick debe ser rechazada", result.isValid)
    }

    @Test
    fun `acepta accion con caracteres alphanumericos y guiones`() {
        val schema = mapOf("type" to "Button", "action" to "save-document_v2")
        val result = validateSchema(schema)
        assertTrue("Accion alfanumerica con guiones debe ser valida", result.isValid)
    }

    @Test
    fun `rechaza schema con mas de 100 nodos hijos`() {
        val children = (1..101).map { mapOf("type" to "Text", "value" to "item$it") }
        val schema = mapOf("type" to "Column", "children" to children)
        val result = validateSchema(schema)
        assertFalse("Mas de 100 hijos debe ser rechazado", result.isValid)
    }

    @Test
    fun `acepta schema con exactamente 100 nodos hijos`() {
        val children = (1..100).map { mapOf("type" to "Text", "value" to "item$it") }
        val schema = mapOf("type" to "Column", "children" to children)
        val result = validateSchema(schema)
        assertTrue("Exactamente 100 hijos debe ser aceptado", result.isValid)
    }
}

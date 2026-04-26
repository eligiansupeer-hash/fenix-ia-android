package com.fenix.ia.presentation.sdui

import kotlinx.serialization.json.Json

/**
 * Valida que un schema JSON cumple el contrato de DynamicUiSchema.
 * Rechaza payloads inválidos ANTES de llegar al compositor.
 *
 * Reglas de seguridad:
 * - Máximo MAX_DEPTH niveles de anidamiento (anti-recursión infinita)
 * - Máximo MAX_COMPONENTS componentes por nivel
 * - Las acciones de ButtonComponent sólo pueden ser identificadores [a-zA-Z0-9_]
 */
object SduiValidator {

    private const val MAX_DEPTH = 5
    private const val MAX_COMPONENTS = 50

    private val SAFE_ACTION_REGEX = Regex("^[a-zA-Z0-9_]{1,64}$")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /**
     * Retorna el schema deserializado si es válido, null si falla cualquier regla.
     */
    fun validate(jsonString: String): DynamicUiSchema? {
        return try {
            val schema = json.decodeFromString<DynamicUiSchema>(jsonString)
            if (schema.version != 1) return null
            if (schema.components.size > MAX_COMPONENTS) return null
            if (!validateComponents(schema.components, depth = 0)) return null
            schema
        } catch (e: Exception) {
            null
        }
    }

    private fun validateComponents(components: List<UiComponent>, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        if (components.size > MAX_COMPONENTS) return false

        return components.all { component ->
            when (component) {
                is UiComponent.ButtonComponent -> {
                    // Acción sólo alfanumérica+guión bajo — previene inyección
                    SAFE_ACTION_REGEX.matches(component.action)
                }
                is UiComponent.ColumnComponent ->
                    validateComponents(component.children, depth + 1)
                is UiComponent.RowComponent ->
                    validateComponents(component.children, depth + 1)
                is UiComponent.CardComponent ->
                    validateComponents(component.children, depth + 1)
                else -> true
            }
        }
    }
}

package com.fenix.ia.presentation.sdui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Esquema JSON para UI dinámica (SDUI).
 * La IA genera un DynamicUiSchema → se valida → se renderiza en Compose.
 *
 * Tipos soportados: text, button, input, column, row, card, divider
 * RESTRICCIÓN R-03: NO se carga código nativo — sólo descriptores JSON.
 */
@Serializable
data class DynamicUiSchema(
    val version: Int = 1,
    val components: List<UiComponent>
)

@Serializable
sealed class UiComponent {

    @Serializable
    @SerialName("text")
    data class TextComponent(
        val text: String,
        val style: TextStyle = TextStyle.BODY,
        val testTag: String = ""
    ) : UiComponent()

    @Serializable
    @SerialName("button")
    data class ButtonComponent(
        val label: String,
        val action: String,   // Identificador de acción — procesado por el ViewModel
        val enabled: Boolean = true,
        val testTag: String = ""
    ) : UiComponent()

    @Serializable
    @SerialName("input")
    data class InputComponent(
        val placeholder: String,
        val key: String,      // Clave para recuperar el valor en el ViewModel
        val multiline: Boolean = false,
        val testTag: String = ""
    ) : UiComponent()

    @Serializable
    @SerialName("column")
    data class ColumnComponent(
        val children: List<UiComponent>
    ) : UiComponent()

    @Serializable
    @SerialName("row")
    data class RowComponent(
        val children: List<UiComponent>
    ) : UiComponent()

    @Serializable
    @SerialName("card")
    data class CardComponent(
        val children: List<UiComponent>
    ) : UiComponent()

    @Serializable
    @SerialName("divider")
    data class DividerComponent(
        val thickness: Int = 1
    ) : UiComponent()
}

@Serializable
enum class TextStyle { HEADING, SUBHEADING, BODY, CAPTION }

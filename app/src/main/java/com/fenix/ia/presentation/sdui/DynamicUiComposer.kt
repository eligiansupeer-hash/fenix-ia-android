package com.fenix.ia.presentation.sdui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compositor de UI dinámica generada por la IA.
 * Recibe un [DynamicUiSchema] validado y lo renderiza con Composables nativos.
 *
 * - onAction: callback del ViewModel que recibe el identificador de acción del botón
 * - onInputChange: callback para capturar valores de campos de entrada (key, value)
 *
 * RESTRICCIÓN R-01: Sin WebView — UI 100% nativa Jetpack Compose.
 * RESTRICCIÓN R-03: Sin carga dinámica de código — sólo descriptores JSON renderizados.
 */
@Composable
fun DynamicUiComposer(
    schema: DynamicUiSchema,
    onAction: (action: String) -> Unit = {},
    onInputChange: (key: String, value: String) -> Unit = { _, _ -> }
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        schema.components.forEach { component ->
            RenderComponent(component, onAction, onInputChange)
        }
    }
}

@Composable
private fun RenderComponent(
    component: UiComponent,
    onAction: (String) -> Unit,
    onInputChange: (String, String) -> Unit
) {
    when (component) {
        is UiComponent.TextComponent -> {
            val style = when (component.style) {
                TextStyle.HEADING -> MaterialTheme.typography.headlineSmall
                TextStyle.SUBHEADING -> MaterialTheme.typography.titleMedium
                TextStyle.BODY -> MaterialTheme.typography.bodyMedium
                TextStyle.CAPTION -> MaterialTheme.typography.labelSmall
            }
            Text(
                text = component.text,
                style = style,
                modifier = Modifier.testTag(component.testTag.ifBlank { "sdui_text" })
            )
        }

        is UiComponent.ButtonComponent -> {
            Button(
                onClick = { if (component.enabled) onAction(component.action) },
                enabled = component.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(component.testTag.ifBlank { "sdui_button_${component.action}" })
            ) {
                Text(component.label)
            }
        }

        is UiComponent.InputComponent -> {
            var value by remember { mutableStateOf("") }
            OutlinedTextField(
                value = value,
                onValueChange = { newVal ->
                    value = newVal
                    onInputChange(component.key, newVal)
                },
                placeholder = { Text(component.placeholder) },
                singleLine = !component.multiline,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(component.testTag.ifBlank { "sdui_input_${component.key}" })
            )
        }

        is UiComponent.ColumnComponent -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                component.children.forEach { child ->
                    RenderComponent(child, onAction, onInputChange)
                }
            }
        }

        is UiComponent.RowComponent -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                component.children.forEach { child ->
                    Box(modifier = Modifier.weight(1f)) {
                        RenderComponent(child, onAction, onInputChange)
                    }
                }
            }
        }

        is UiComponent.CardComponent -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    component.children.forEach { child ->
                        RenderComponent(child, onAction, onInputChange)
                    }
                }
            }
        }

        is UiComponent.DividerComponent -> {
            HorizontalDivider(thickness = component.thickness.dp)
        }
    }
}

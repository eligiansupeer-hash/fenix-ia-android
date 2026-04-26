package com.fenix.ia

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.fenix.ia.presentation.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NODO-14 — Smoke Tests E2E (End-to-End)
 * Ejecutar con dispositivo/emulador conectado:
 *   ./gradlew connectedDebugAndroidTest --tests "com.fenix.ia.E2ESmokeTest"
 *
 * Cubre los flujos críticos del manual FENIX IA:
 *   1. Crear proyecto → abrir → crear chat → enviar mensaje → verificar streaming
 *   2. Configurar API key → verificar que no aparece en texto plano
 *   3. Árbol de documentos visible en el proyecto
 *   4. Pantalla principal carga sin crash
 *   5. Navegación sin crash
 *   6. Regenerar último mensaje del asistente
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class E2ESmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    // -----------------------------------------------------------------------
    // Test 1: Flujo completo — proyecto → chat → mensaje → streaming
    // -----------------------------------------------------------------------
    @Test
    fun `flujo completo crear proyecto chat y enviar mensaje`() {
        composeTestRule.onNodeWithContentDescription("Nuevo proyecto").performClick()
        composeTestRule.onNodeWithTag("project_name_field").performTextInput("Álgebra Lineal")
        composeTestRule.onNodeWithTag("project_system_prompt")
            .performTextInput("Eres un tutor de álgebra lineal")
        composeTestRule.onNodeWithText("Guardar").performClick()

        composeTestRule.onNodeWithText("Álgebra Lineal").assertIsDisplayed()

        composeTestRule.onNodeWithText("Álgebra Lineal").performClick()
        composeTestRule.onNodeWithContentDescription("Nuevo chat").performClick()

        composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()

        composeTestRule.onNodeWithTag("chat_input").performTextInput("¿Qué es una matriz?")
        composeTestRule.onNodeWithTag("send_button").performClick()

        // Indicador de streaming debe aparecer
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("streaming_indicator")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Respuesta completa en máximo 30s (LLM real)
        composeTestRule.waitUntil(timeoutMillis = 30000) {
            composeTestRule.onAllNodesWithTag("assistant_message")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onAllNodesWithTag("assistant_message")
            .onFirst()
            .assertTextContains("", substring = true)
    }

    // -----------------------------------------------------------------------
    // Test 2: API keys — seguridad (AGENTS.md restricción)
    // -----------------------------------------------------------------------
    @Test
    fun `api key guardada no aparece en texto plano en la UI`() {
        composeTestRule.onNodeWithContentDescription("Configuración").performClick()

        composeTestRule.onNodeWithTag("gemini_key_field").performTextInput("AIza-test-key-abc123")
        composeTestRule.onNodeWithText("Guardar").performClick()

        // El proveedor aparece como configurado (con tilde)
        composeTestRule.onNodeWithText("GEMINI ✓").assertIsDisplayed()

        // CRÍTICO: la key en texto plano NO debe aparecer en ningún lugar
        composeTestRule.onNodeWithText("AIza-test-key-abc123").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------
    // Test 3: Árbol de documentos visible
    // -----------------------------------------------------------------------
    @Test
    fun `arbol de documentos visible en detalle de proyecto`() {
        // Navega a un proyecto existente (o crea uno si no hay)
        composeTestRule.onNodeWithContentDescription("Nuevo proyecto").performClick()
        composeTestRule.onNodeWithTag("project_name_field").performTextInput("Proyecto Test")
        composeTestRule.onNodeWithText("Guardar").performClick()

        composeTestRule.onNodeWithText("Proyecto Test").performClick()

        // El árbol de documentos debe existir (aunque esté vacío)
        composeTestRule.onNodeWithTag("document_tree").assertExists()
    }

    // -----------------------------------------------------------------------
    // Test 4: Pantalla principal carga sin crash
    // -----------------------------------------------------------------------
    @Test
    fun `pantalla de proyectos carga correctamente`() {
        composeTestRule.onRoot().assertExists()
        composeTestRule.onRoot().assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // Test 5: Navegación sin crash
    // -----------------------------------------------------------------------
    @Test
    fun `navegacion a configuracion y regreso no causa crash`() {
        composeTestRule.onNodeWithContentDescription("Configuración").performClick()
        composeTestRule.onRoot().assertIsDisplayed()

        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        composeTestRule.onRoot().assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // Test 6: Regenerar último mensaje del asistente
    // -----------------------------------------------------------------------
    @Test
    fun `boton regenerar aparece en ultimo mensaje del asistente`() {
        // Prerrequisito: navegar a un chat con al menos un mensaje del asistente
        composeTestRule.onNodeWithContentDescription("Nuevo proyecto").performClick()
        composeTestRule.onNodeWithTag("project_name_field").performTextInput("Test Regen")
        composeTestRule.onNodeWithText("Guardar").performClick()
        composeTestRule.onNodeWithText("Test Regen").performClick()
        composeTestRule.onNodeWithContentDescription("Nuevo chat").performClick()

        // Envía un mensaje y espera la respuesta
        composeTestRule.onNodeWithTag("chat_input").performTextInput("Hola")
        composeTestRule.onNodeWithTag("send_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 30000) {
            composeTestRule.onAllNodesWithTag("assistant_message")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // El botón de regenerar (contentDescription = "Regenerar respuesta") debe existir
        composeTestRule.onNodeWithContentDescription("Regenerar respuesta").assertIsDisplayed()

        // Al tocar regenerar no debe crashear y debe iniciar streaming
        composeTestRule.onNodeWithContentDescription("Regenerar respuesta").performClick()

        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule.onAllNodesWithTag("streaming_indicator")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}

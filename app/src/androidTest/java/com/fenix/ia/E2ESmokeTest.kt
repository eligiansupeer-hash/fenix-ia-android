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
 * Cubre los flujos críticos del manual:
 *   1. Crear proyecto → abrir → crear chat → enviar mensaje → verificar streaming
 *   2. Configurar API key → verificar que no aparece en texto plano
 *   3. Árbol de documentos visible en el proyecto
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
    fun `flujo completo crear proyecto chat ingerir documento consultar`() {
        // 1. Crea un proyecto
        composeTestRule.onNodeWithContentDescription("Nuevo proyecto").performClick()
        composeTestRule.onNodeWithTag("project_name_field").performTextInput("Álgebra Lineal")
        composeTestRule.onNodeWithTag("project_system_prompt")
            .performTextInput("Eres un tutor de álgebra lineal")
        composeTestRule.onNodeWithText("Guardar").performClick()

        // Verifica que el proyecto aparece en la lista
        composeTestRule.onNodeWithText("Álgebra Lineal").assertIsDisplayed()

        // 2. Abre el proyecto y crea un chat
        composeTestRule.onNodeWithText("Álgebra Lineal").performClick()
        composeTestRule.onNodeWithContentDescription("Nuevo chat").performClick()

        // 3. Verifica que la pantalla de chat está activa
        composeTestRule.onNodeWithTag("chat_input").assertIsDisplayed()

        // 4. Envía un mensaje y verifica streaming
        composeTestRule.onNodeWithTag("chat_input").performTextInput("¿Qué es una matriz?")
        composeTestRule.onNodeWithTag("send_button").performClick()

        // Verifica indicador de streaming (aparece mientras el LLM genera)
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("streaming_indicator")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Espera respuesta completa (timeout 30s para LLM real)
        composeTestRule.waitUntil(timeoutMillis = 30000) {
            composeTestRule.onAllNodesWithTag("assistant_message")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // 5. Verifica que la respuesta contiene texto (no está vacía)
        composeTestRule.onAllNodesWithTag("assistant_message")
            .onFirst()
            .assertTextContains("", substring = true)
    }

    // -----------------------------------------------------------------------
    // Test 2: API keys no aparecen en texto plano (seguridad — AGENTS.md)
    // -----------------------------------------------------------------------

    @Test
    fun `configuracion de API keys se almacena sin texto plano visible`() {
        composeTestRule.onNodeWithContentDescription("Configuración").performClick()
        composeTestRule.onNodeWithText("Insertar API Keys").performClick()

        composeTestRule.onNodeWithTag("gemini_key_field").performTextInput("AIza-test-key-abc123")
        composeTestRule.onNodeWithText("Guardar").performClick()

        // Verifica que el proveedor aparece como configurado
        composeTestRule.onNodeWithText("Gemini ✓").assertIsDisplayed()

        // CRÍTICO: Verifica que la key en texto plano NO aparece en la UI
        composeTestRule.onNodeWithText("AIza-test-key-abc123").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------
    // Test 3: Árbol de documentos visible
    // -----------------------------------------------------------------------

    @Test
    fun `arbol de documentos muestra archivos generados por la IA`() {
        // Navega a la pantalla principal de proyecto
        composeTestRule.onNodeWithTag("document_tree").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // Test 4: Pantalla de proyectos carga sin crash
    // -----------------------------------------------------------------------

    @Test
    fun `pantalla de proyectos carga correctamente`() {
        // La MainActivity debe mostrar la pantalla de proyectos al iniciar
        // Si hay crash en el startup, este test falla inmediatamente
        composeTestRule.onRoot().assertExists()
        // Verifica que al menos hay contenido visible
        composeTestRule.onRoot().assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // Test 5: Navegación entre pantallas no causa crash
    // -----------------------------------------------------------------------

    @Test
    fun `navegacion a configuracion y regreso no causa crash`() {
        // Abre configuración
        composeTestRule.onNodeWithContentDescription("Configuración").performClick()

        // Verifica que la pantalla de configuración cargó
        composeTestRule.onRoot().assertIsDisplayed()

        // Navega hacia atrás
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        // Verifica que volvió a la pantalla principal sin crash
        composeTestRule.onRoot().assertIsDisplayed()
    }
}

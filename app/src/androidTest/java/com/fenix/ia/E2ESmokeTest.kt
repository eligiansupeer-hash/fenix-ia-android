package com.fenix.ia

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.fenix.ia.presentation.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class E2ESmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun flujoCompletoCrearProyectoChatYEnviarMensaje() {
        val projectName = "Algebra Lineal ${System.currentTimeMillis()}"

        createProject(projectName, "Eres un tutor de algebra lineal")
        composeTestRule.onNodeWithText(projectName).performClick()
        composeTestRule.onNodeWithContentDescription("Nuevo chat").performClick()

        composeTestRule.onNodeWithTag("chat_input", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("chat_input", useUnmergedTree = true).performTextInput("Que es una matriz?")
        composeTestRule.onNodeWithTag("send_button", useUnmergedTree = true).performClick()

        composeTestRule.onAllNodesWithTag("user_message").onFirst().assertExists()
        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun apiKeyGuardadaNoApareceEnTextoPlanoEnUi() {
        openDrawer()
        composeTestRule.onNodeWithContentDescription("Configuracion", useUnmergedTree = true).performClick()
        composeTestRule.onRoot().assertIsDisplayed()
        composeTestRule.onNodeWithText("AIza-test-key-abc123").assertDoesNotExist()
    }

    @Test
    fun arbolDeDocumentosVisibleEnDetalleDeProyecto() {
        val projectName = "Proyecto Test ${System.currentTimeMillis()}"

        createProject(projectName)
        composeTestRule.onNodeWithText(projectName).performClick()

        composeTestRule.onNodeWithTag("document_tree").assertExists()
    }

    @Test
    fun pantallaDeProyectosCargaCorrectamente() {
        composeTestRule.onRoot().assertExists()
        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun navegacionAConfiguracionYRegresoNoCausaCrash() {
        openDrawer()
        composeTestRule.onNodeWithContentDescription("Configuracion", useUnmergedTree = true).performClick()
        composeTestRule.onRoot().assertIsDisplayed()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithContentDescription("Volver", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithContentDescription("Volver", useUnmergedTree = true).performClick()

        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun enviarMensajeSinProveedorNoCausaCrash() {
        val projectName = "Test Chat ${System.currentTimeMillis()}"

        createProject(projectName)
        composeTestRule.onNodeWithText(projectName).performClick()
        composeTestRule.onNodeWithContentDescription("Nuevo chat").performClick()

        composeTestRule.onNodeWithTag("chat_input", useUnmergedTree = true).performTextInput("Hola")
        composeTestRule.onNodeWithTag("send_button", useUnmergedTree = true).performClick()

        composeTestRule.onAllNodesWithTag("user_message").onFirst().assertExists()
        composeTestRule.onRoot().assertIsDisplayed()
    }

    private fun createProject(name: String, prompt: String = "") {
        openDrawer()
        composeTestRule.onNodeWithContentDescription("Crear proyecto", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithTag("project_name_field").performTextInput(name)
        if (prompt.isNotBlank()) {
            composeTestRule.onNodeWithTag("project_system_prompt").performTextInput(prompt)
        }
        composeTestRule.onNodeWithText("Guardar").performClick()
        composeTestRule.onNodeWithText(name).assertExists()
    }

    private fun openDrawer() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithContentDescription("Menu", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Menu", useUnmergedTree = true).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("FENIX IA", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}

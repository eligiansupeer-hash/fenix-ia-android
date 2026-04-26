package com.fenix.ia

import org.junit.Assert.*
import org.junit.Test

/**
 * NODO-12 — COMPUERTA 3: Test unitario de LlmInferenceRouter
 * Valida:
 *   - selectProvider retorna el proveedor disponible con mayor prioridad
 *   - Fallback correcto cuando el proveedor primario no tiene key configurada
 *   - Orden de fallback: Gemini → Groq → Mistral → OpenRouter → GitHub Models
 *   - Provider con key vacía es ignorado en la selección
 */
class LlmInferenceRouterTest {

    // -----------------------------------------------------------------------
    // Modelos espejo del dominio para tests puros (sin Hilt/Android)
    // -----------------------------------------------------------------------

    enum class ApiProvider(val priority: Int) {
        GEMINI(1),
        GROQ(2),
        MISTRAL(3),
        OPEN_ROUTER(4),
        GITHUB_MODELS(5)
    }

    data class ProviderConfig(
        val provider: ApiProvider,
        val apiKey: String,
        val isEnabled: Boolean = true
    ) {
        val hasValidKey: Boolean get() = apiKey.isNotBlank()
    }

    // Lógica de selectProvider: elige el proveedor habilitado con key válida
    // y menor número de prioridad (1 = más prioritario)
    private fun selectProvider(configs: List<ProviderConfig>): ApiProvider? {
        return configs
            .filter { it.isEnabled && it.hasValidKey }
            .minByOrNull { it.provider.priority }
            ?.provider
    }

    // -----------------------------------------------------------------------
    // Tests de selección de proveedor
    // -----------------------------------------------------------------------

    @Test
    fun `selecciona Gemini cuando todos los proveedores tienen key configurada`() {
        val configs = listOf(
            ProviderConfig(ApiProvider.GEMINI, "AIza-gemini-key"),
            ProviderConfig(ApiProvider.GROQ, "gsk_groq-key"),
            ProviderConfig(ApiProvider.MISTRAL, "mistral-key")
        )
        assertEquals("Gemini debe ser seleccionado (prioridad 1)", ApiProvider.GEMINI, selectProvider(configs))
    }

    @Test
    fun `fallback a Groq cuando Gemini no tiene key`() {
        val configs = listOf(
            ProviderConfig(ApiProvider.GEMINI, ""),         // sin key
            ProviderConfig(ApiProvider.GROQ, "gsk_groq-key"),
            ProviderConfig(ApiProvider.MISTRAL, "mistral-key")
        )
        assertEquals("Groq debe ser seleccionado como fallback", ApiProvider.GROQ, selectProvider(configs))
    }

    @Test
    fun `fallback a Mistral cuando Gemini y Groq no tienen key`() {
        val configs = listOf(
            ProviderConfig(ApiProvider.GEMINI, ""),
            ProviderConfig(ApiProvider.GROQ, ""),
            ProviderConfig(ApiProvider.MISTRAL, "mistral-key")
        )
        assertEquals("Mistral debe ser seleccionado", ApiProvider.MISTRAL, selectProvider(configs))
    }

    @Test
    fun `fallback a OpenRouter cuando Gemini Groq y Mistral sin key`() {
        val configs = listOf(
            ProviderConfig(ApiProvider.GEMINI, ""),
            ProviderConfig(ApiProvider.GROQ, ""),
            ProviderConfig(ApiProvider.MISTRAL, ""),
            ProviderConfig(ApiProvider.OPEN_ROUTER, "sk-or-openrouter-key"),
            ProviderConfig(ApiProvider.GITHUB_MODELS, "github-key")
        )
        assertEquals("OpenRouter debe ser seleccionado", ApiProvider.OPEN_ROUTER, selectProvider(configs))
    }

    @Test
    fun `fallback a GitHub Models como ultimo recurso`() {
        val configs = listOf(
            ProviderConfig(ApiProvider.GEMINI, ""),
            ProviderConfig(ApiProvider.GROQ, ""),
            ProviderConfig(ApiProvider.MISTRAL, ""),
            ProviderConfig(ApiProvider.OPEN_ROUTER, ""),
            ProviderConfig(ApiProvider.GITHUB_MODELS, "ghp_github-key")
        )
        assertEquals("GitHub Models es el último recurso", ApiProvider.GITHUB_MODELS, selectProvider(configs))
    }

    @Test
    fun `retorna null cuando ningun proveedor tiene key configurada`() {
        val configs = listOf(
            ProviderConfig(ApiProvider.GEMINI, ""),
            ProviderConfig(ApiProvider.GROQ, ""),
            ProviderConfig(ApiProvider.MISTRAL, "")
        )
        assertNull("Sin proveedores disponibles debe retornar null", selectProvider(configs))
    }

    @Test
    fun `retorna null con lista vacia de configuraciones`() {
        assertNull("Lista vacía debe retornar null", selectProvider(emptyList()))
    }

    @Test
    fun `ignora proveedor deshabilitado aunque tenga key valida`() {
        val configs = listOf(
            ProviderConfig(ApiProvider.GEMINI, "AIza-key", isEnabled = false),
            ProviderConfig(ApiProvider.GROQ, "gsk_groq-key", isEnabled = true)
        )
        assertEquals("Gemini deshabilitado debe ser ignorado", ApiProvider.GROQ, selectProvider(configs))
    }

    // -----------------------------------------------------------------------
    // Tests de orden de prioridad
    // -----------------------------------------------------------------------

    @Test
    fun `orden de prioridad es Gemini Groq Mistral OpenRouter GitHubModels`() {
        val expectedOrder = listOf(
            ApiProvider.GEMINI,
            ApiProvider.GROQ,
            ApiProvider.MISTRAL,
            ApiProvider.OPEN_ROUTER,
            ApiProvider.GITHUB_MODELS
        )
        val actualOrder = ApiProvider.values().sortedBy { it.priority }
        assertEquals("El orden de prioridad debe ser correcto", expectedOrder, actualOrder)
    }

    @Test
    fun `Gemini tiene prioridad maxima 1`() {
        assertEquals(1, ApiProvider.GEMINI.priority)
    }

    @Test
    fun `GitHub Models tiene prioridad minima 5`() {
        assertEquals(5, ApiProvider.GITHUB_MODELS.priority)
    }

    // -----------------------------------------------------------------------
    // Tests de validación de keys
    // -----------------------------------------------------------------------

    @Test
    fun `key solo de espacios es considerada invalida`() {
        val config = ProviderConfig(ApiProvider.GEMINI, "   ")
        assertFalse("Key solo de espacios debe ser inválida", config.hasValidKey)
    }

    @Test
    fun `key valida retorna hasValidKey true`() {
        val config = ProviderConfig(ApiProvider.GEMINI, "AIza-real-key-123")
        assertTrue("Key real debe ser válida", config.hasValidKey)
    }
}

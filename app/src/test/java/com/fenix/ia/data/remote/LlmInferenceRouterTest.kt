package com.fenix.ia.data.remote

import com.fenix.ia.domain.model.ApiProvider
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LlmInferenceRouterTest {

    private val router = LlmInferenceRouter(mockk(), mockk())

    /**
     * El umbral para elegir Gemini es 60_000 tokens (context window amplio).
     * Cualquier valor > 60_000 debe seleccionar Gemini.
     * 50_000 ya no alcanza — el umbral subió para que documentos medianos
     * puedan ir completos a Groq/Mistral sin desperdiciar el context window de Gemini.
     */
    @Test
    fun `selectProvider elige GEMINI para contextos grandes`() {
        val provider = router.selectProvider(61_000, TaskType.DOCUMENT_ANALYSIS)
        assertEquals(ApiProvider.GEMINI, provider)
    }

    @Test
    fun `selectProvider elige GROQ para contextos bajo el umbral de Gemini`() {
        // 50_000 tokens <= 60_000 → no activa Gemini → default es GROQ para DOCUMENT_ANALYSIS
        val provider = router.selectProvider(50_000, TaskType.DOCUMENT_ANALYSIS)
        assertEquals(ApiProvider.GROQ, provider)
    }

    @Test
    fun `selectProvider elige MISTRAL para generación de código`() {
        val provider = router.selectProvider(1000, TaskType.CODE_GENERATION)
        assertEquals(ApiProvider.MISTRAL, provider)
    }

    @Test
    fun `selectProvider elige GROQ para chat rápido`() {
        val provider = router.selectProvider(500, TaskType.FAST_CHAT)
        assertEquals(ApiProvider.GROQ, provider)
    }

    @Test
    fun `getFallback de GROQ retorna OPENROUTER`() {
        val fallback = router.getFallback(ApiProvider.GROQ)
        assertEquals(ApiProvider.OPENROUTER, fallback)
    }

    @Test
    fun `getFallback de GEMINI retorna OPENROUTER`() {
        val fallback = router.getFallback(ApiProvider.GEMINI)
        assertEquals(ApiProvider.OPENROUTER, fallback)
    }

    @Test
    fun `preferredProvider tiene prioridad sobre lógica automática`() {
        val provider = router.selectProvider(61_000, TaskType.CODE_GENERATION, ApiProvider.GROQ)
        assertEquals(ApiProvider.GROQ, provider)
    }
}

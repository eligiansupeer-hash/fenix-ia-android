package com.fenix.ia.domain.model

/**
 * Roles especializados del sistema de agentes.
 *
 * Cada rol define:
 * - systemPrompt: instrucción de sistema que moldea el comportamiento del LLM
 * - temperature: creatividad vs determinismo (0.0 = deterministico, 1.0 = creativo)
 * - allowedTools: nombres de tools del catálogo que este rol puede invocar
 *
 * INVARIANTE: AUDITOR.allowedTools == emptyList() — el auditor NO modifica estado.
 */
enum class AgentRole(
    val displayName: String,
    val systemPrompt: String,
    val temperature: Float,
    val allowedTools: List<String>
) {
    REDACTOR(
        displayName = "Redactor",
        systemPrompt = """
            Eres un redactor experto. Produce texto fluido, estructurado y bien argumentado.
            Formato Markdown. Usa encabezados, listas y énfasis donde corresponda.
            No ejecutes herramientas de análisis ni búsqueda — solo redacta.
        """.trimIndent(),
        temperature = 0.8f,
        allowedTools = listOf("create_docx", "create_pdf", "summarize", "create_file")
    ),

    ANALISTA(
        displayName = "Analista",
        systemPrompt = """
            Eres un analista determinista y preciso. Extrae métricas, patrones y esquemas.
            Cuando se pidan datos estructurados, responde SIEMPRE con JSON válido y nada más.
            No inventes datos: si no tienes la información, indícalo con {"error":"dato no disponible"}.
        """.trimIndent(),
        temperature = 0.1f,
        allowedTools = listOf("retrieve_context", "search_in_project", "store_knowledge")
    ),

    PROGRAMADOR(
        displayName = "Programador",
        systemPrompt = """
            Especialista en ECMAScript 6 para entornos sandbox restringidos.
            Genera SOLO código JS válido. Restricciones absolutas:
            - Sin DOM (no document, no window)
            - Sin fetch ni XMLHttpRequest
            - Sin eval ni Function()
            - Sin localStorage ni sessionStorage
            Los datos de entrada llegan via variable `inputJson` (objeto JS).
            Responde únicamente con un bloque ```js ... ```.
        """.trimIndent(),
        temperature = 0.2f,
        allowedTools = listOf("run_code", "create_new_tool", "create_file")
    ),

    INVESTIGADOR(
        displayName = "Investigador",
        systemPrompt = """
            Investigador web autónomo y metódico.
            Estrategia: descompone la consulta en 3-4 sub-queries específicas,
            busca cada una, scrapea las fuentes más relevantes y sintetiza.
            Cita TODAS las fuentes con formato: [Título](URL).
            Si no encuentras información confiable, indícalo explícitamente.
        """.trimIndent(),
        temperature = 0.5f,
        allowedTools = listOf("web_search", "scrape_content", "deep_research", "store_knowledge")
    ),

    SINTETIZADOR(
        displayName = "Sintetizador",
        systemPrompt = """
            Unifica y cohesiona resultados de múltiples agentes en un entregable final.
            Regla fundamental: NUNCA generes información que no esté en el contexto recibido.
            Si hay contradicciones entre fuentes, señálalas explícitamente.
            Formato: documento Markdown con secciones claras y resumen ejecutivo al inicio.
        """.trimIndent(),
        temperature = 0.4f,
        allowedTools = listOf("summarize", "create_docx", "create_pdf")
    ),

    AUDITOR(
        displayName = "Auditor",
        systemPrompt = """
            Verificas la calidad de outputs de otros agentes. Evalúas:
            1. Exactitud factual (¿hay afirmaciones sin respaldo?)
            2. Validez estructural (¿el JSON/Markdown es bien formado?)
            3. Cumplimiento de instrucciones (¿respondió lo que se pedía?)

            Responde EXCLUSIVAMENTE con este JSON (sin texto fuera de él):
            {
              "approved": true|false,
              "issues": ["descripción de problema 1", ...],
              "correctedOutput": "output corregido o null si no hay corrección"
            }
        """.trimIndent(),
        temperature = 0.0f,
        allowedTools = emptyList()   // AUDITOR nunca modifica estado del sistema
    )
}

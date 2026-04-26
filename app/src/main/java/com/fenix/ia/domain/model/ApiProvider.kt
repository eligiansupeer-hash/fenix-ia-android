package com.fenix.ia.domain.model

enum class ApiProvider { GEMINI, GROQ, MISTRAL, OPENROUTER, GITHUB_MODELS }

data class ApiKey(
    val provider: ApiProvider,
    val encryptedKey: String   // NUNCA almacenes el valor raw aquí
)

package com.fenix.ia.domain.model

enum class ApiProvider {
    GEMINI,
    GROQ,
    MISTRAL,
    OPENROUTER,
    GITHUB_MODELS,
    LOCAL_ON_DEVICE    // modelo MediaPipe on-device — no requiere API key
}

data class ApiKey(
    val provider: ApiProvider,
    val encryptedKey: String   // NUNCA almacenes el valor raw aquí
)

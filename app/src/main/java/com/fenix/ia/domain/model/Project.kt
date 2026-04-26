package com.fenix.ia.domain.model

// INVARIANTE: sin imports android.* — módulo domain es puro Kotlin
data class Project(
    val id: String,           // UUID
    val name: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long
)

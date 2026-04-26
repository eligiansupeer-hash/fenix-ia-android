package com.fenix.ia.domain.model

data class DocumentNode(
    val id: String,
    val projectId: String,
    val name: String,
    val absolutePath: String,
    val mimeType: String,
    val semanticSummary: String,  // Resumen ligero para el array de contexto
    val createdAt: Long,
    val isChecked: Boolean = false  // Checkpoint para selección de contexto
)

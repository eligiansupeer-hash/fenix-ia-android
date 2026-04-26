package com.fenix.ia.domain.model

/**
 * Modelo de dominio para documentos indexados.
 * - uri: Content URI de Scoped Storage (Android 10+), nunca ruta absoluta
 * - semanticSummary: resumen breve generado durante la ingesta para mostrar en contexto de chat
 * - isIndexed: indica si RagEngine procesó el documento
 * - isChecked: selección de usuario para incluir en contexto de chat
 */
data class DocumentNode(
    val id: String,
    val projectId: String,
    val name: String,
    val uri: String,            // Content URI (reemplaza absolutePath)
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val semanticSummary: String = "",
    val isIndexed: Boolean = false,
    val isChecked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

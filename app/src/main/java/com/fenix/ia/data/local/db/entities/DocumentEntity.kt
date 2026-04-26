package com.fenix.ia.data.local.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidad Room para documentos del proyecto.
 * - uri: Content URI de Scoped Storage (no rutas absolutas — R-04 / API 30+)
 * - semanticSummary: resumen breve generado durante la ingesta (máx ~300 chars)
 * - isIndexed: true cuando RagEngine terminó de indexar en ObjectBox
 * - sizeBytes: para mostrar tamaño en UI sin releer el archivo
 */
@Entity(
    tableName = "documents",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val uri: String,              // Content URI (reemplaza absolutePath — compatible API 30+)
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val semanticSummary: String = "",
    val isIndexed: Boolean = false,
    val isChecked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

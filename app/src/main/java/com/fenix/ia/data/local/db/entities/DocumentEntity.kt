package com.fenix.ia.data.local.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val absolutePath: String,
    val mimeType: String,
    val semanticSummary: String,
    val createdAt: Long,
    val isChecked: Boolean = false
)

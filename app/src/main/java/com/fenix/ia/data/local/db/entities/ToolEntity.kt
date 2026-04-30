package com.fenix.ia.data.local.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tools",
    indices = [Index("name", unique = true)]
)
data class ToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val inputSchema: String,
    val outputSchema: String,
    val permissions: String,       // JSON array serializado: ["INTERNET","WRITE_EXTERNAL_STORAGE"]
    val executionType: String,     // ToolExecutionType.name
    val jsBody: String?,
    val isEnabled: Boolean,
    val isUserGenerated: Boolean,
    val createdAt: Long
)

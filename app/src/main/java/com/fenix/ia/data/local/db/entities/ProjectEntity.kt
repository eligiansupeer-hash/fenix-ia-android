package com.fenix.ia.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long
)

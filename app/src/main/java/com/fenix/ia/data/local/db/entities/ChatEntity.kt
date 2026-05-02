package com.fenix.ia.data.local.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// P4: Eliminada FK estricta a ProjectEntity — projectId es nullable
// para permitir conversaciones globales sin proyecto asociado
@Entity(
    tableName = "chats",
    indices = [Index("projectId")]
)
data class ChatEntity(
    @PrimaryKey val id: String,
    val projectId: String?, // Nullable: null = Chat General huérfano de proyecto
    val title: String,
    val createdAt: Long
)

package com.fenix.ia.data.local.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// P5: Tabla intermedia N:M para habilitar/deshabilitar herramientas por chat
@Entity(
    tableName = "chat_tools",
    primaryKeys = ["chatId", "toolId"],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId"), Index("toolId")]
)
data class ChatToolEntity(
    val chatId: String,
    val toolId: String,
    val isEnabled: Boolean
)

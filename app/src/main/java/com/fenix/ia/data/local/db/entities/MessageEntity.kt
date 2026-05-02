package com.fenix.ia.data.local.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class,
        parentColumns = ["id"],
        childColumns = ["chatId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("chatId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val role: String,   // "USER" | "ASSISTANT" | "SYSTEM"
    val content: String,
    val attachmentUris: String?, // P6: URIs separadas por coma, null si sin adjuntos
    val timestamp: Long
)

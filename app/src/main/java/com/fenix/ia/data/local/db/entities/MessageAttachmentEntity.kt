package com.fenix.ia.data.local.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("messageId"),
        Index("chatId"),
        Index("projectId"),
        Index("documentId")
    ]
)
data class MessageAttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val chatId: String,
    val projectId: String,
    val documentId: String,
    val mimeType: String,
    val status: String,
    val checksum: String,
    val sourceUri: String,
    val privateUri: String,
    val createdAt: Long
)

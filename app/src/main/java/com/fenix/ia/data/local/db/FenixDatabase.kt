package com.fenix.ia.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.fenix.ia.data.local.db.dao.*
import com.fenix.ia.data.local.db.entities.*

/**
 * Base de datos Room de FENIX IA.
 *
 * version 2 → añade tabla `tools`          (MIGRATION_1_2)
 * version 3 → P4: chats.projectId nullable
 *           → P5: tabla chat_tools (N:M)
 *           → P6: messages.attachmentUris   (MIGRATION_2_3)
 *
 * exportSchema = false: evita que KSP busque schema JSON.
 */
@Database(
    entities = [
        ProjectEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        DocumentEntity::class,
        ToolEntity::class,
        ChatToolEntity::class      // P5
    ],
    version = 3,
    exportSchema = false
)
abstract class FenixDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun documentDao(): DocumentDao
    abstract fun toolDao(): ToolDao
    abstract fun chatToolDao(): ChatToolDao   // P5
}

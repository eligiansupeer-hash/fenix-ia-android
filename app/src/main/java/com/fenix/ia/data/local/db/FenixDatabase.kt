package com.fenix.ia.data.local.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.fenix.ia.data.local.db.dao.*
import com.fenix.ia.data.local.db.entities.*

@Database(
    entities = [
        ProjectEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        DocumentEntity::class,
        ToolEntity::class
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2)
    ]
)
abstract class FenixDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun documentDao(): DocumentDao
    abstract fun toolDao(): ToolDao
}

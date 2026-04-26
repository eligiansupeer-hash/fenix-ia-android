package com.fenix.ia.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.fenix.ia.data.local.db.dao.*
import com.fenix.ia.data.local.db.entities.*

@Database(
    entities = [
        ProjectEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        DocumentEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class FenixDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun documentDao(): DocumentDao
}

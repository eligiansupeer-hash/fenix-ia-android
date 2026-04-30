package com.fenix.ia.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.fenix.ia.data.local.db.dao.*
import com.fenix.ia.data.local.db.entities.*

/**
 * Base de datos Room de FENIX IA.
 *
 * version 2: agrega tabla `tools` (NODO-A1 del manual evolutivo v2)
 *
 * MIGRACION v1→v2: gestionada por fallbackToDestructiveMigration() en AppModule.
 * En producción futura se reemplazaría por Migration(1,2) explícita.
 *
 * exportSchema = false: evita que KSP busque 1.json que nunca se generó.
 */
@Database(
    entities = [
        ProjectEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        DocumentEntity::class,
        ToolEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FenixDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun documentDao(): DocumentDao
    abstract fun toolDao(): ToolDao
}

package com.fenix.ia.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fenix.ia.data.local.db.FenixDatabase
import com.fenix.ia.data.local.db.dao.*
import com.fenix.ia.data.local.objectbox.EmbeddingModel
import com.fenix.ia.data.local.objectbox.ObjectBoxStore
import com.fenix.ia.data.local.objectbox.TFLiteEmbeddingModel
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.serialization.kotlinx.json.*
import io.objectbox.BoxStore
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import javax.inject.Singleton

// ── Migración v1 → v2: crea tabla tools ──────────────────────────────────────
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tools (
                id              TEXT    NOT NULL PRIMARY KEY,
                name            TEXT    NOT NULL,
                description     TEXT    NOT NULL,
                inputSchema     TEXT    NOT NULL,
                outputSchema    TEXT    NOT NULL,
                permissions     TEXT    NOT NULL,
                executionType   TEXT    NOT NULL,
                jsBody          TEXT,
                isEnabled       INTEGER NOT NULL DEFAULT 1,
                isUserGenerated INTEGER NOT NULL DEFAULT 0,
                createdAt       INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_tools_name ON tools(name)"
        )
    }
}

// ── Migración v2 → v3: P4 chats nullable, P5 chat_tools, P6 attachmentUris ──
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // P4: Recrear tabla chats con projectId nullable (eliminar FK estricta)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `chats_new` " +
            "(`id` TEXT NOT NULL, `projectId` TEXT, `title` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        database.execSQL(
            "INSERT INTO `chats_new` (`id`, `projectId`, `title`, `createdAt`) " +
            "SELECT `id`, `projectId`, `title`, `createdAt` FROM `chats`"
        )
        database.execSQL("DROP TABLE `chats`")
        database.execSQL("ALTER TABLE `chats_new` RENAME TO `chats`")
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chats_projectId` ON `chats` (`projectId`)"
        )

        // P5: Tabla N:M chat_tools
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_tools` " +
            "(`chatId` TEXT NOT NULL, `toolId` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, " +
            "PRIMARY KEY(`chatId`, `toolId`), " +
            "FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON DELETE CASCADE, " +
            "FOREIGN KEY(`toolId`) REFERENCES `tools`(`id`) ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_tools_chatId` ON `chat_tools` (`chatId`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_tools_toolId` ON `chat_tools` (`toolId`)"
        )

        // P6: Columna attachmentUris en messages
        database.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentUris` TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideRoomDatabase(@ApplicationContext context: Context): FenixDatabase {
        return Room.databaseBuilder(
            context,
            FenixDatabase::class.java,
            "fenix_ia_db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    @Provides fun provideProjectDao(db: FenixDatabase)  = db.projectDao()
    @Provides fun provideChatDao(db: FenixDatabase)     = db.chatDao()
    @Provides fun provideMessageDao(db: FenixDatabase)  = db.messageDao()
    @Provides fun provideDocumentDao(db: FenixDatabase) = db.documentDao()
    @Provides fun provideToolDao(db: FenixDatabase)     = db.toolDao()
    @Provides fun provideChatToolDao(db: FenixDatabase) = db.chatToolDao() // P5

    // ── ObjectBox ─────────────────────────────────────────────────────────────
    @Provides
    @Singleton
    fun provideObjectBoxStore(@ApplicationContext context: Context): BoxStore {
        ObjectBoxStore.init(context)
        return ObjectBoxStore.store
    }

    // ── Ktor HttpClient — OkHttp + TLS fingerprint Chrome 120+ (Fase 10) ─────
    @Provides
    @Singleton
    fun provideKtorClient(): HttpClient {
        val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(
                CipherSuite.TLS_AES_128_GCM_SHA256,
                CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
            )
            .build()

        return HttpClient(OkHttp) {
            install(ContentNegotiation) { json() }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000L
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis  = 120_000L
            }
            engine {
                config {
                    connectionSpecs(listOf(tlsSpec, ConnectionSpec.CLEARTEXT))
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddingModule {
    @Binds
    @Singleton
    abstract fun bindEmbeddingModel(impl: TFLiteEmbeddingModel): EmbeddingModel
}

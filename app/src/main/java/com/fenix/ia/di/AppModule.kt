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
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import okhttp3.CipherSuite
import javax.inject.Singleton

/**
 * Migración v1 → v2: crea la tabla tools.
 */
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
        .addMigrations(MIGRATION_1_2)
        .build()
    }

    @Provides fun provideProjectDao(db: FenixDatabase)  = db.projectDao()
    @Provides fun provideChatDao(db: FenixDatabase)     = db.chatDao()
    @Provides fun provideMessageDao(db: FenixDatabase)  = db.messageDao()
    @Provides fun provideDocumentDao(db: FenixDatabase) = db.documentDao()
    @Provides fun provideToolDao(db: FenixDatabase)     = db.toolDao()

    // ── ObjectBox ─────────────────────────────────────────────────────────────
    @Provides
    @Singleton
    fun provideObjectBoxStore(@ApplicationContext context: Context): BoxStore {
        ObjectBoxStore.init(context)
        return ObjectBoxStore.store
    }

    // ── Ktor HttpClient — OkHttp engine con TLS fingerprint Chrome (Fase 10) ──
    @Provides
    @Singleton
    fun provideKtorClient(): HttpClient {
        // Perfil TLS equivalente a Chrome 120+ para evadir WAF/Cloudflare
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

/**
 * Binding EmbeddingModel → TFLiteEmbeddingModel.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddingModule {
    @Binds
    @Singleton
    abstract fun bindEmbeddingModel(impl: TFLiteEmbeddingModel): EmbeddingModel
}

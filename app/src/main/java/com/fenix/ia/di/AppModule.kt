package com.fenix.ia.di

import android.content.Context
import androidx.room.Room
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
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.serialization.kotlinx.json.*
import io.objectbox.BoxStore
import javax.inject.Singleton

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
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides fun provideProjectDao(db: FenixDatabase) = db.projectDao()
    @Provides fun provideChatDao(db: FenixDatabase) = db.chatDao()
    @Provides fun provideMessageDao(db: FenixDatabase) = db.messageDao()
    @Provides fun provideDocumentDao(db: FenixDatabase) = db.documentDao()

    // ── ObjectBox ─────────────────────────────────────────────────────────────
    @Provides
    @Singleton
    fun provideObjectBoxStore(@ApplicationContext context: Context): BoxStore {
        ObjectBoxStore.init(context)
        return ObjectBoxStore.store
    }

    @Provides
    @Singleton
    fun provideKtorClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) { json() }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000L
                connectTimeoutMillis = 15_000L
            }
            engine {
                requestTimeout = 120_000
                threadsCount = 4
            }
        }
    }
}

/**
 * Binding de interfaz EmbeddingModel → TFLiteEmbeddingModel.
 * Módulo separado para permitir reemplazo en tests (FakeEmbeddingModel).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddingModule {
    @Binds
    @Singleton
    abstract fun bindEmbeddingModel(impl: TFLiteEmbeddingModel): EmbeddingModel
}

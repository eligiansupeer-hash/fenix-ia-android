package com.fenix.ia.di

import android.content.Context
import androidx.room.Room
import com.fenix.ia.data.local.db.FenixDatabase
import com.fenix.ia.data.local.db.dao.*
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
        .fallbackToDestructiveMigration() // Reemplazar con Migrations en prod
        .build()
    }

    @Provides fun provideProjectDao(db: FenixDatabase) = db.projectDao()
    @Provides fun provideChatDao(db: FenixDatabase) = db.chatDao()
    @Provides fun provideMessageDao(db: FenixDatabase) = db.messageDao()
    @Provides fun provideDocumentDao(db: FenixDatabase) = db.documentDao()

    @Provides
    @Singleton
    fun provideKtorClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) { json() }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000L  // 2 min para modelos lentos
                connectTimeoutMillis = 15_000L
            }
            engine {
                requestTimeout = 120_000
                threadsCount = 4  // Limitado para dispositivos de bajos recursos
            }
        }
    }
}

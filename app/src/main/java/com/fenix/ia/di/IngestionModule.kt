package com.fenix.ia.di

import android.content.Context
import com.fenix.ia.ingestion.DocxTextExtractor
import com.fenix.ia.ingestion.PdfTextExtractor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provee los extractores de texto para NODO-06.
 * Son @Singleton porque no tienen estado mutable — compartir instancias es seguro.
 */
@Module
@InstallIn(SingletonComponent::class)
object IngestionModule {

    @Provides
    @Singleton
    fun providePdfTextExtractor(@ApplicationContext context: Context): PdfTextExtractor =
        PdfTextExtractor(context)

    @Provides
    @Singleton
    fun provideDocxTextExtractor(@ApplicationContext context: Context): DocxTextExtractor =
        DocxTextExtractor(context)
}

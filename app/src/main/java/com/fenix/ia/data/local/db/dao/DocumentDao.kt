package com.fenix.ia.data.local.db.dao

import androidx.room.*
import com.fenix.ia.data.local.db.entities.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun getDocumentsByProject(projectId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentDocuments(limit: Int): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    suspend fun getDocumentById(documentId: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    @Query("UPDATE documents SET isChecked = :isChecked WHERE id = :documentId")
    suspend fun updateCheckpoint(documentId: String, isChecked: Boolean)

    @Query("UPDATE documents SET status = 'processing', errorMessage = '' WHERE id = :documentId")
    suspend fun markAsProcessing(documentId: String)

    @Query("UPDATE documents SET isIndexed = 1, status = 'indexed', errorMessage = '', semanticSummary = :summary WHERE id = :documentId")
    suspend fun markAsIndexed(documentId: String, summary: String = "Indexado")

    @Query("UPDATE documents SET isIndexed = 0, status = 'no_text', errorMessage = '', semanticSummary = :summary WHERE id = :documentId")
    suspend fun markAsNoText(documentId: String, summary: String = "Sin texto extraible")

    @Query("UPDATE documents SET isIndexed = 0, status = 'error', errorMessage = :error, semanticSummary = 'Error al procesar' WHERE id = :documentId")
    suspend fun markAsFailed(documentId: String, error: String)

    @Query("SELECT * FROM documents WHERE projectId = :projectId AND isChecked = 1")
    suspend fun getCheckedDocuments(projectId: String): List<DocumentEntity>
}

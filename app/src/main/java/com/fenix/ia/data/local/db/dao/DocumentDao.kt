package com.fenix.ia.data.local.db.dao

import androidx.room.*
import com.fenix.ia.data.local.db.entities.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun getDocumentsByProject(projectId: String): Flow<List<DocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    @Query("UPDATE documents SET isChecked = :isChecked WHERE id = :documentId")
    suspend fun updateCheckpoint(documentId: String, isChecked: Boolean)

    @Query("SELECT * FROM documents WHERE projectId = :projectId AND isChecked = 1")
    suspend fun getCheckedDocuments(projectId: String): List<DocumentEntity>
}

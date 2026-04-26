package com.fenix.ia.domain.repository

import com.fenix.ia.domain.model.DocumentNode
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getDocumentsByProject(projectId: String): Flow<List<DocumentNode>>
    suspend fun insertDocument(document: DocumentNode)
    suspend fun deleteDocument(documentId: String)
    suspend fun updateCheckpoint(documentId: String, isChecked: Boolean)
    suspend fun getCheckedDocuments(projectId: String): List<DocumentNode>
}

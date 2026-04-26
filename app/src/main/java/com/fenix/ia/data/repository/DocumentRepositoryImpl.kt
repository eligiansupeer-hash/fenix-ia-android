package com.fenix.ia.data.repository

import com.fenix.ia.data.local.db.dao.DocumentDao
import com.fenix.ia.data.local.db.entity.DocumentNodeEntity
import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val ragEngine: RagEngine
) : DocumentRepository {

    override fun getDocumentsByProject(projectId: String): Flow<List<DocumentNode>> =
        documentDao.getDocumentsByProject(projectId).map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun insertDocument(document: DocumentNode) {
        documentDao.insertDocument(document.toEntity())
    }

    override suspend fun deleteDocument(documentId: String) {
        // Elimina chunks vectoriales antes de borrar metadatos
        ragEngine.deleteDocumentChunks(documentId)
        documentDao.deleteDocument(documentId)
    }

    override suspend fun updateCheckpoint(documentId: String, isChecked: Boolean) {
        documentDao.updateCheckpoint(documentId, isChecked)
    }

    override suspend fun getCheckedDocuments(projectId: String): List<DocumentNode> =
        documentDao.getCheckedDocuments(projectId).map { it.toDomain() }

    // ── Mappers ──────────────────────────────────────────────────────────────

    private fun DocumentNodeEntity.toDomain() = DocumentNode(
        id = id,
        projectId = projectId,
        name = name,
        mimeType = mimeType,
        uri = uri,
        sizeBytes = sizeBytes,
        isIndexed = isIndexed,
        isChecked = isChecked,
        createdAt = createdAt
    )

    private fun DocumentNode.toEntity() = DocumentNodeEntity(
        id = id,
        projectId = projectId,
        name = name,
        mimeType = mimeType,
        uri = uri,
        sizeBytes = sizeBytes,
        isIndexed = isIndexed,
        isChecked = isChecked,
        createdAt = createdAt
    )
}

package com.fenix.ia.sistema

/**
 * Contracts for isolated Fenix document modules.
 *
 * These modules are intentionally separated from regular chats and projects.
 * They own their inputs, tools, evidence, audit trail, limits and exports.
 */
enum class FenixModuleType(val displayName: String, val active: Boolean) {
    SISTEMA_FENIX_V21("Sistema Fenix v2.1", true),
    FENIX_DOCUMENTAL_SLOT_2("Sistema Fenix documental 2", false),
    FENIX_DOCUMENTAL_SLOT_3("Sistema Fenix documental 3", false)
}

data class FenixDocumentModule(
    val type: FenixModuleType,
    val description: String,
    val requiredCapabilities: Set<SistemaFenixCapability>,
    val maxRuntimeMs: Long,
    val maxOutputChars: Int
)

object FenixDocumentModuleRegistry {
    val modules: List<FenixDocumentModule> = listOf(
        FenixDocumentModule(
            type = FenixModuleType.SISTEMA_FENIX_V21,
            description = "Produccion documental academica por partes A-F con evidencia, no invencion y export DOCX.",
            requiredCapabilities = setOf(
                SistemaFenixCapability.READ_MODULE_FILES,
                SistemaFenixCapability.CREATE_MODULE_FILES,
                SistemaFenixCapability.CALL_APPROVED_AI_PROVIDER
            ),
            maxRuntimeMs = 20 * 60 * 1000L,
            maxOutputChars = 250_000
        ),
        FenixDocumentModule(
            type = FenixModuleType.FENIX_DOCUMENTAL_SLOT_2,
            description = "Reservado para un futuro sistema documental con razonamiento propio.",
            requiredCapabilities = emptySet(),
            maxRuntimeMs = 0L,
            maxOutputChars = 0
        ),
        FenixDocumentModule(
            type = FenixModuleType.FENIX_DOCUMENTAL_SLOT_3,
            description = "Reservado para un segundo futuro sistema documental con razonamiento propio.",
            requiredCapabilities = emptySet(),
            maxRuntimeMs = 0L,
            maxOutputChars = 0
        )
    )

    fun activeModules(): List<FenixDocumentModule> = modules.filter { it.type.active }
    fun byType(type: FenixModuleType): FenixDocumentModule = modules.first { it.type == type }
}

data class FenixRun(
    val id: String,
    val moduleType: FenixModuleType,
    val title: String,
    val status: FenixRunStatus,
    val inputRefs: List<String>,
    val createdAt: Long,
    val updatedAt: Long = createdAt
)

enum class FenixRunStatus {
    DRAFT,
    INVENTORY,
    GENERATING,
    VALIDATING,
    EXPORTED,
    ERROR,
    CANCELLED
}

data class FenixEvidence(
    val id: String,
    val runId: String,
    val sourceRef: String,
    val sectionId: String,
    val quoteOrParaphrase: String,
    val page: Int? = null,
    val offsetStart: Int? = null,
    val offsetEnd: Int? = null,
    val checksum: String = ""
)

data class FenixSection(
    val id: String,
    val runId: String,
    val key: String,
    val title: String,
    val status: FenixSectionStatus,
    val content: String,
    val evidenceIds: List<String> = emptyList()
)

enum class FenixSectionStatus {
    PENDING,
    READY,
    NEEDS_EVIDENCE,
    ERROR
}

data class FenixExportManifest(
    val runId: String,
    val moduleType: FenixModuleType,
    val files: List<String>,
    val sha256ByFile: Map<String, String>,
    val exportedAt: Long
)

data class SistemaFenixRequest(
    val requestId: String,
    val moduleType: FenixModuleType = FenixModuleType.SISTEMA_FENIX_V21,
    val instruction: String,
    val inputRefs: List<String> = emptyList(),
    val allowedCapabilities: Set<SistemaFenixCapability> = emptySet(),
    val maxRuntimeMs: Long = FenixDocumentModuleRegistry.byType(moduleType).maxRuntimeMs,
    val maxOutputChars: Int = FenixDocumentModuleRegistry.byType(moduleType).maxOutputChars
)

data class SistemaFenixResult(
    val requestId: String,
    val runId: String = "",
    val success: Boolean,
    val outputPreview: String,
    val artifactRefs: List<String> = emptyList(),
    val evidenceRefs: List<String> = emptyList(),
    val error: String = ""
)

enum class SistemaFenixCapability {
    READ_MODULE_FILES,
    CREATE_MODULE_FILES,
    READ_MODULE_EVIDENCE,
    CALL_APPROVED_TOOLS,
    CALL_APPROVED_AI_PROVIDER
}

interface SistemaFenixEnvironment {
    suspend fun execute(request: SistemaFenixRequest): SistemaFenixResult
}

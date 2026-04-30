package com.fenix.ia.domain.model

data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val inputSchema: String,
    val outputSchema: String,
    val permissions: List<String>,
    val executionType: ToolExecutionType,
    val jsBody: String?,
    val isEnabled: Boolean = true,
    val isUserGenerated: Boolean = false,
    val createdAt: Long
)

enum class ToolExecutionType { NATIVE_KOTLIN, JAVASCRIPT, HTTP_EXTERNAL }

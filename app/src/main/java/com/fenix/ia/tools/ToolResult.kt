package com.fenix.ia.tools

sealed class ToolResult {
    /** Ejecucion exitosa. outputJson es un JSON string valido. */
    data class Success(val outputJson: String) : ToolResult()

    /** Error de ejecucion. isRetryable=true cuando el fallo es transitorio (red, timeout). */
    data class Error(
        val message: String,
        val isRetryable: Boolean = false
    ) : ToolResult()
}

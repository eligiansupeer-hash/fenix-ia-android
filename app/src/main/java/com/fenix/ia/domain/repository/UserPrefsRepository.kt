package com.fenix.ia.domain.repository

/**
 * Preferencias de usuario persistidas localmente.
 * Incluye parámetros ajustables del comportamiento del agente.
 */
interface UserPrefsRepository {
    suspend fun getMaxAgenticIterations(): Int
    suspend fun setMaxAgenticIterations(value: Int)
}

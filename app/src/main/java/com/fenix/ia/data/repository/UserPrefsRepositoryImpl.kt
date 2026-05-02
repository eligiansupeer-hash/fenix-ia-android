package com.fenix.ia.data.repository

import android.content.Context
import com.fenix.ia.domain.repository.UserPrefsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPrefsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : UserPrefsRepository {

    companion object {
        private const val PREFS_NAME = "fenix_user_prefs"
        private const val KEY_MAX_AGENTIC_ITERATIONS = "max_agentic_iterations"
        const val DEFAULT_MAX_AGENTIC_ITERATIONS = 10
        const val MIN_ITERATIONS = 1
        const val MAX_ITERATIONS = 25
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun getMaxAgenticIterations(): Int =
        prefs.getInt(KEY_MAX_AGENTIC_ITERATIONS, DEFAULT_MAX_AGENTIC_ITERATIONS)
            .coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)

    override suspend fun setMaxAgenticIterations(value: Int) {
        prefs.edit()
            .putInt(KEY_MAX_AGENTIC_ITERATIONS, value.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS))
            .apply()
    }
}

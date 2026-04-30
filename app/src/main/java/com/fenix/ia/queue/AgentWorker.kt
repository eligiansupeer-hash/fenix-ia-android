package com.fenix.ia.queue

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fenix.ia.orchestrator.OrchestratorEngine
import com.fenix.ia.orchestrator.OrchestratorEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker de Hilt para ejecutar un agente en background vía WorkManager.
 * Soporta encadenamiento: el output de un worker puede ser input del siguiente.
 *
 * Reintentos automáticos hasta 3 veces en caso de error transitorio.
 * En error definitivo (attempt >= 3) devuelve Result.failure() con mensaje.
 */
@HiltWorker
class AgentWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: OrchestratorEngine
) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_ROLE = "agent_role"
        const val KEY_INSTR = "instruction"
        const val KEY_PROJECT = "project_id"
        const val KEY_OUTPUT = "agent_output"
        private const val MAX_RETRIES = 3
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val role = inputData.getString(KEY_ROLE)
            ?: return@withContext Result.failure(workDataOf("error" to "role no especificado"))
        val instr = inputData.getString(KEY_INSTR)
            ?: return@withContext Result.failure(workDataOf("error" to "instruction no especificada"))
        val projectId = inputData.getString(KEY_PROJECT)
            ?: return@withContext Result.failure(workDataOf("error" to "project_id no especificado"))

        try {
            var finalOutput = ""
            orchestrator.executeWorkflow(
                goal = instr,
                projectId = projectId
            ).collect { event ->
                if (event is OrchestratorEvent.WorkflowDone) {
                    finalOutput = event.finalOutput
                }
            }
            Result.success(workDataOf(KEY_OUTPUT to finalOutput))
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(workDataOf("error" to (e.message ?: "Error desconocido")))
            }
        }
    }
}

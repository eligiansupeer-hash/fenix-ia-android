package com.fenix.ia.queue

import android.content.Context
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fenix.ia.domain.model.AgentRole
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.orchestrator.OrchestratorEngine
import com.fenix.ia.orchestrator.OrchestratorEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cola dual de tareas de agentes:
 *
 * COLA ACADÉMICA (tiempo real, urgente):
 *   Semaphore(1) — máximo 1 tarea pesada concurrente.
 *   Retorna un Flow de OrchestratorEvent para que la UI pueda observar el progreso en vivo.
 *
 * COLA DE PROGRAMACIÓN (diferible):
 *   WorkManager encadenado: generate -> audit.
 *   Respeta batería (setRequiresBatteryNotLow) y reintentos automáticos (max 3).
 */
@Singleton
class TaskQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: OrchestratorEngine
) {
    // Solo 1 tarea académica pesada puede ejecutarse en paralelo
    private val academicSemaphore = Semaphore(1)

    /**
     * Encola una tarea académica (análisis, redacción, investigación).
     * El Flow se suspende hasta que el semáforo esté libre, luego emite
     * todos los OrchestratorEvent en tiempo real.
     */
    fun enqueueAcademic(
        goal: String,
        projectId: String,
        provider: ApiProvider? = null
    ): Flow<OrchestratorEvent> = channelFlow {
        academicSemaphore.withPermit {
            orchestrator.executeWorkflow(goal, projectId, provider).collect { send(it) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Encola una tarea de programación diferible vía WorkManager.
     * Cadena: AgentWorker(PROGRAMADOR) -> AgentWorker(AUDITOR)
     * El resultado final queda en el OutputData del worker Auditor.
     */
    fun enqueueProgramming(instruction: String, projectId: String) {
        val generateRequest = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInputData(
                workDataOf(
                    AgentWorker.KEY_ROLE to AgentRole.PROGRAMADOR.name,
                    AgentWorker.KEY_INSTR to instruction,
                    AgentWorker.KEY_PROJECT to projectId
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        val auditRequest = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInputData(
                workDataOf(
                    AgentWorker.KEY_ROLE to AgentRole.AUDITOR.name,
                    AgentWorker.KEY_PROJECT to projectId
                )
            )
            .build()

        WorkManager.getInstance(context)
            .beginWith(generateRequest)
            .then(auditRequest)
            .enqueue()
    }
}

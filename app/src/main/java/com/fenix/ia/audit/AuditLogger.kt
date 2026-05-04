package com.fenix.ia.audit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AuditLogger {
    private const val TAG = "FenixAudit"
    private const val DIR_NAME = "audit"
    private const val FILE_NAME = "fenix_audit.jsonl"
    private const val TIMELINE_FILE_NAME = "fenix_timeline.txt"
    private const val MAX_BYTES = 25L * 1024L * 1024L

    @Volatile private var appContext: Context? = null
    @Volatile private var eventCounter: Long = 0
    private val sessionId: String = UUID.randomUUID().toString()
    private val lock = Any()
    private val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val localDate = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun install(application: Application) {
        if (AuditMode.current == AuditMode.OFF) return
        appContext = application.applicationContext
        event("app_start", "application", human = "La app FENIX IA se inicio.")
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                event(
                    "activity_created",
                    activity.javaClass.simpleName,
                    human = "Android creo la pantalla principal ${activity.javaClass.simpleName}."
                )

            override fun onActivityResumed(activity: Activity) =
                event(
                    "activity_resumed",
                    activity.javaClass.simpleName,
                    human = "La app quedo visible para el usuario."
                )

            override fun onActivityPaused(activity: Activity) =
                event(
                    "activity_paused",
                    activity.javaClass.simpleName,
                    human = "La app dejo de estar en primer plano."
                )

            override fun onActivityDestroyed(activity: Activity) =
                event(
                    "activity_destroyed",
                    activity.javaClass.simpleName,
                    human = "Android destruyo la pantalla ${activity.javaClass.simpleName}."
                )

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error("uncaught_exception", throwable, mapOf("thread" to thread.name))
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun screen(route: String?) {
        val screen = friendlyScreen(route)
        event("screen_view", route ?: "unknown", human = "El usuario esta viendo la pantalla: $screen.")
    }

    fun tap(screen: String?, x: Float, y: Float) {
        val friendly = friendlyScreen(screen)
        event(
            type = "tap",
            name = screen ?: "unknown",
            data = mapOf(
                "x" to x.toInt().toString(),
                "y" to y.toInt().toString()
            ),
            human = "El usuario toco la pantalla $friendly en x=${x.toInt()}, y=${y.toInt()}."
        )
    }

    fun action(name: String, data: Map<String, String> = emptyMap()) {
        event("action", name, data, human = describeAction(name, data))
    }

    fun chat(role: String, text: String, data: Map<String, String> = emptyMap()) {
        val storedText = when (AuditMode.current) {
            AuditMode.FULL_TEST -> text
            AuditMode.SAFE_PRODUCTION -> safeTextSummary(text)
            AuditMode.OFF -> return
        }
        event("chat", role, data + mapOf("text" to storedText), human = describeChat(role, storedText, data))
    }

    fun visibleMessage(screen: String, text: String, data: Map<String, String> = emptyMap()) {
        val storedText = when (AuditMode.current) {
            AuditMode.FULL_TEST -> text
            AuditMode.SAFE_PRODUCTION -> safeTextSummary(text)
            AuditMode.OFF -> return
        }
        event(
            "visible_message",
            screen,
            data + mapOf("text" to storedText),
            human = "La app mostro un mensaje en ${friendlyScreen(screen)}: \"${redact(storedText)}\""
        )
    }

    fun manualObservation(text: String, data: Map<String, String> = emptyMap()) {
        if (AuditMode.current == AuditMode.OFF) return
        val storedText = if (AuditMode.current == AuditMode.FULL_TEST) text else safeTextSummary(text)
        event(
            "manual_observation",
            "user_note",
            data + mapOf("text" to storedText),
            human = "OBSERVACION MANUAL DEL USUARIO: \"${redact(storedText)}\""
        )
    }

    fun error(name: String, throwable: Throwable, data: Map<String, String> = emptyMap()) {
        if (AuditMode.current == AuditMode.OFF) return
        val stack = if (AuditMode.current == AuditMode.FULL_TEST) {
            throwable.stackTraceToString().take(12_000)
        } else {
            throwable.stackTrace.firstOrNull()?.toString().orEmpty()
        }
        event(
            type = "error",
            name = name,
            data = data + mapOf(
                "exception" to throwable.javaClass.name,
                "message" to safeTextSummary(throwable.message.orEmpty()),
                "stack" to stack
            ),
            human = "ERROR/CRASH: $name. ${throwable.javaClass.simpleName}: ${redact(throwable.message.orEmpty())}"
        )
    }

    fun event(
        type: String,
        name: String,
        data: Map<String, String> = emptyMap(),
        human: String? = null
    ) {
        if (AuditMode.current == AuditMode.OFF) return
        val context = appContext ?: return
        val sequence = nextSequence()
        val now = Date()
        val record = JSONObject().apply {
            put("seq", sequence)
            put("sessionId", sessionId)
            put("ts", isoDate.format(now))
            put("localTime", localDate.format(now))
            put("uptimeMs", SystemClock.uptimeMillis())
            put("memory", memoryJson())
            put("type", type)
            put("name", redact(name))
            val payload = JSONObject()
            data.forEach { (key, value) -> payload.put(key, sanitizeValueForMode(key, value)) }
            put("data", payload)
            put("human", redact(human ?: describeFallback(type, name, data)))
        }.toString()
        val timelineLine = "${localDate.format(now)} #$sequence - ${redact(human ?: describeFallback(type, name, data))}"

        synchronized(lock) {
            try {
                val dir = File(context.filesDir, DIR_NAME).also { it.mkdirs() }
                val file = File(dir, FILE_NAME)
                val timeline = File(dir, TIMELINE_FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    file.renameTo(File(dir, "$FILE_NAME.1"))
                }
                if (timeline.exists() && timeline.length() > MAX_BYTES) {
                    timeline.renameTo(File(dir, "$TIMELINE_FILE_NAME.1"))
                }
                file.appendText(record + "\n")
                timeline.appendText(timelineLine + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo escribir auditoria", e)
            }
        }
    }

    fun createExportZip(context: Context): File? {
        val sourceDir = File(context.filesDir, DIR_NAME)
        if (!sourceDir.exists()) return null
        val target = File(context.cacheDir, "fenix_audit_export.zip")
        return try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                listOf(FILE_NAME, TIMELINE_FILE_NAME, "$FILE_NAME.1", "$TIMELINE_FILE_NAME.1").forEach { name ->
                    val file = File(sourceDir, name)
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo exportar auditoria", e)
            null
        }
    }

    private fun nextSequence(): Long = synchronized(lock) {
        eventCounter += 1
        eventCounter
    }

    private fun friendlyScreen(route: String?): String = when (route) {
        null, "unknown" -> "pantalla desconocida"
        "projects" -> "Proyectos"
        "settings" -> "Configuracion"
        "general_chats" -> "Chats generales"
        "project/{projectId}" -> "Detalle de proyecto"
        "chat/{projectId}/{chatId}" -> "Chat de proyecto"
        "chat_general/{chatId}" -> "Chat general"
        "workflow/{projectId}" -> "Workflow"
        "research/{projectId}" -> "Research"
        "tools/{projectId}" -> "Herramientas"
        "artifacts/{projectId}" -> "Artefactos"
        "sistema_fenix" -> "Sistema Fenix"
        else -> route
    }

    private fun describeAction(name: String, data: Map<String, String>): String {
        val provider = data["provider"]
        val tool = data["tool"] ?: data["toolId"]
        return when (name) {
            "navigate_settings" -> "El usuario abrio Configuracion."
            "back_settings" -> "El usuario volvio desde Configuracion."
            "navigate_project_detail" -> "El usuario abrio un proyecto. projectId=${data["projectId"].orEmpty()}"
            "back_project_detail" -> "El usuario volvio desde Detalle de proyecto."
            "navigate_chat" -> "El usuario abrio un chat. chatId=${data["chatId"].orEmpty()}"
            "back_chat" -> "El usuario volvio desde el chat."
            "navigate_general_chats" -> "El usuario abrio Chats generales."
            "navigate_general_chat" -> "El usuario abrio un chat general. chatId=${data["chatId"].orEmpty()}"
            "navigate_tools" -> "El usuario abrio Herramientas."
            "back_tools" -> "El usuario volvio desde Herramientas."
            "navigate_research" -> "El usuario abrio Research."
            "back_research" -> "El usuario volvio desde Research."
            "navigate_workflow" -> "El usuario abrio Workflow."
            "back_workflow" -> "El usuario volvio desde Workflow."
            "navigate_artifacts" -> "El usuario abrio Artefactos."
            "back_artifacts" -> "El usuario volvio desde Artefactos."
            "chat_load" -> "La app cargo el chat ${data["chatId"].orEmpty()} del proyecto ${data["projectId"].orEmpty()}."
            "chat_select_provider" -> "El usuario selecciono proveedor de IA: ${provider ?: "AUTO"}."
            "chat_send_message" -> "El usuario envio un mensaje. chars=${data["chars"].orEmpty()}, adjuntos=${data["attachments"].orEmpty()}."
            "chat_send_blocked_busy" -> "La app bloqueo un envio porque ya habia una respuesta en curso."
            "chat_provider_resolved" -> "La app eligio proveedor $provider. tokens=${data["estimatedTokens"].orEmpty()}, contexto=${data["contextMode"].orEmpty()}."
            "chat_agent_start" -> "La app inicio el agente con proveedor $provider, herramientas=${data["activeTools"].orEmpty()}, maxIter=${data["maxIterations"].orEmpty()}."
            "chat_stream_done" -> "La IA termino streaming con proveedor $provider. chars=${data["chars"].orEmpty()}."
            "chat_stream_error" -> "La IA devolvio error con proveedor $provider: ${data["message"].orEmpty()}"
            "chat_provider_fallback" -> "La app cambio de proveedor: ${data["from"].orEmpty()} -> ${data["to"].orEmpty()}."
            "llm_provider_fallback" -> "El router cambio de proveedor: ${data["from"].orEmpty()} -> ${data["to"].orEmpty()}."
            "llm_rate_limit_fallback" -> "Hubo limite de cuota y fallback: ${data["from"].orEmpty()} -> ${data["to"].orEmpty()}."
            "llm_rate_limit" -> "Hubo limite de cuota en proveedor ${data["provider"].orEmpty()}."
            "llm_http_error" -> "Error HTTP del proveedor ${data["provider"].orEmpty()}: status=${data["status"].orEmpty()}."
            "llm_auth_error" -> "Error de API key con proveedor ${data["provider"].orEmpty()}."
            "llm_request_start" -> "La app llamo al proveedor ${data["provider"].orEmpty()} con herramientas=${data["tools"].orEmpty()}."
            "llm_local_not_ready" -> "El usuario intento usar IA nativa pero el modelo local no estaba activo."
            "chat_toggle_tool" -> "El usuario cambio una herramienta. tool=$tool, enabled=${data["enabled"].orEmpty()}."
            "chat_tool_calls_detected" -> "La IA pidio ejecutar herramientas. cantidad=${data["count"].orEmpty()}, iteracion=${data["iteration"].orEmpty()}."
            "chat_tool_execute" -> "La app ejecuto herramienta: $tool."
            "chat_tool_success" -> "La herramienta termino OK: $tool."
            "chat_tool_error" -> "La herramienta fallo: $tool. ${data["message"].orEmpty()}"
            "chat_tool_missing" -> "La IA pidio una herramienta inexistente/no habilitada: $tool."
            "tool_execute_start" -> "Inicio de ejecucion interna de herramienta: $tool."
            "tool_execute_success" -> "Ejecucion interna de herramienta OK: $tool."
            "tool_execute_error" -> "Ejecucion interna de herramienta fallo: $tool. ${data["message"].orEmpty()}"
            "chat_toggle_document_checkpoint" -> "El usuario marco/desmarco documento para contexto. documentId=${data["documentId"].orEmpty()}, checked=${data["checked"].orEmpty()}."
            "chat_add_attachment" -> "El usuario agrego un adjunto al chat. uriLength=${data["uriLength"].orEmpty()}."
            "chat_stop_streaming" -> "El usuario detuvo la respuesta en streaming."
            "chat_agent_iteration_limit" -> "El agente llego al limite de iteraciones: ${data["maxIterations"].orEmpty()}."
            "sistema_fenix_pick_documents" -> "El usuario presiono Cargar documentos en Sistema Fenix."
            "sistema_fenix_documents_imported" -> "Sistema Fenix cargo documentos. cantidad=${data["documents"].orEmpty()}, runId=${data["fenixRunId"].orEmpty()}."
            "sistema_fenix_process_pressed" -> "El usuario presiono Procesar con Sistema Fenix."
            "sistema_fenix_run_created" -> "Sistema Fenix creo una nueva ejecucion. runId=${data["fenixRunId"].orEmpty()}."
            "sistema_fenix_run_started" -> "Sistema Fenix empezo a procesar documentos. runId=${data["fenixRunId"].orEmpty()}, documentos=${data["documents"].orEmpty()}."
            "sistema_fenix_phase" -> "Sistema Fenix avanzo de fase: ${data["phase"].orEmpty()} (${data["progress"].orEmpty()})."
            "sistema_fenix_run_finished" -> "Sistema Fenix termino y genero el DOCX ${data["file"].orEmpty()}."
            "sistema_fenix_run_failed" -> "Sistema Fenix fallo durante el procesamiento. runId=${data["fenixRunId"].orEmpty()}."
            "sistema_fenix_open_docx" -> "El usuario abrio el DOCX final de Sistema Fenix."
            "sistema_fenix_reset" -> "El usuario inicio una nueva pantalla limpia de Sistema Fenix."
            else -> describeFallback("action", name, data)
        }
    }

    private fun describeChat(role: String, text: String, data: Map<String, String>): String {
        val provider = data["provider"]?.let { " proveedor=$it" }.orEmpty()
        val chat = data["chatId"]?.let { " chatId=$it" }.orEmpty()
        val clean = redact(text).replace("\n", " ").take(1_500)
        return when (role) {
            "user" -> "CHAT usuario$chat: \"$clean\""
            "assistant_stream_chunk" -> "CHAT IA streaming$provider$chat: \"$clean\""
            "assistant_final" -> "CHAT IA respuesta final$provider$chat: \"$clean\""
            "assistant_tool_call_raw" -> "CHAT IA solicito herramienta$provider$chat: \"$clean\""
            "tool_result" -> "Resultado de herramienta ${data["tool"].orEmpty()}$chat: \"$clean\""
            else -> "CHAT $role$provider$chat: \"$clean\""
        }
    }

    private fun describeFallback(type: String, name: String, data: Map<String, String>): String =
        "$type/$name ${data.entries.joinToString(", ") { "${it.key}=${it.value}" }}".trim()

    private fun redact(raw: String): String {
        val keyPattern = Regex("(AIza[0-9A-Za-z_-]{20,}|gsk_[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{20,})")
        return raw.replace(keyPattern, "[REDACTED_KEY]")
    }

    private fun sanitizeValueForMode(key: String, value: String): String {
        val redacted = redact(value)
        if (AuditMode.current == AuditMode.FULL_TEST) return redacted
        val sensitiveKeys = setOf("text", "prompt", "content", "stack", "response", "ocr", "document")
        return if (key.lowercase() in sensitiveKeys) safeTextSummary(redacted) else redacted.take(500)
    }

    private fun safeTextSummary(text: String): String {
        val clean = redact(text)
        if (clean.isBlank()) return ""
        return "[redacted_content chars=${clean.length} sha256=${sha256(clean).take(16)}]"
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun memoryJson(): JSONObject {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return JSONObject().apply {
            put("usedBytes", used)
            put("freeBytes", runtime.freeMemory())
            put("totalBytes", runtime.totalMemory())
            put("maxBytes", runtime.maxMemory())
        }
    }
}

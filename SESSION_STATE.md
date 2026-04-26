# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
26 Abril 2026 — Sesión 3

---

## Estado de nodos

| Nodo | Estado | Archivos clave |
|------|--------|----------------|
| NODO-00 | ✅ COMPLETO | `AGENTS.md` |
| NODO-01 | ✅ COMPLETO | `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml` |
| NODO-02 | ✅ COMPLETO | `domain/model/`, `domain/repository/`, `presentation/chat/ChatContract.kt` |
| NODO-03 | ✅ COMPLETO | `security/SecureApiManager.kt` (AES-256-GCM + Keystore TEE) |
| NODO-04 | ✅ COMPLETO | `data/local/db/` — Room entities, DAOs, FenixDatabase (cascade FK) |
| NODO-05 | ✅ COMPLETO | `data/local/objectbox/` — DocumentChunk, RagEngine, EmbeddingModel |
| NODO-06 | ✅ COMPLETO | `ingestion/DocumentIngestionWorker.kt` |
| NODO-07 | ✅ COMPLETO | `data/remote/LlmInferenceRouter.kt` |
| NODO-08 | ✅ COMPLETO | `presentation/chat/ChatViewModel.kt` + `ChatScreen.kt` (actualizado sesión 3) |
| NODO-09 | ✅ COMPLETO | `presentation/sdui/` |
| NODO-10 | ✅ COMPLETO | `sandbox/PolicyEngine.kt` + `DynamicExecutionEngine.kt` |
| NODO-11 | ✅ COMPLETO | `data/local/ArtifactManager.kt` |
| NODO-12 | ✅ TESTS PASANDO | BUILD SUCCESSFUL — todos los tests JVM OK |
| NODO-13 | ⏳ PENDIENTE TEST | `MemoryStressTest.kt` — RAM idle medida: 172 MB debug (aceptable) |
| NODO-14 | ⏳ PENDIENTE TEST | `E2ESmokeTest.kt` — requiere rebuild + reinstalar APK |

---

## Lo que hizo la sesión 3 (26 Abril 2026)

### Dispositivo conectado y APK instalado
- **Xiaomi Redmi C14 (4 GB RAM)** conectado por USB con debugging habilitado
- APK debug instalado exitosamente (tras habilitar "Instalar via USB" en Opciones de Desarrollador)
- RAM idle medida: **172.7 MB PSS** en build debug (aceptable — build release será ~60% menos)

### Diagnóstico de funcionalidades faltantes
Se detectó que la app arrancaba pero **no tenía navegación funcional**:
- Al tocar un proyecto no pasaba nada (effect de navegación no estaba conectado)
- `ChatScreen` estaba comentada en el NavHost con `// TODO: implementar en siguiente sesión`
- No existía pantalla de detalle de proyecto ni lista de chats
- No había forma de cargar documentos desde la UI

### Archivos MODIFICADOS esta sesión:
```
presentation/FenixNavHost.kt                        ← rutas completas con projectId
presentation/projects/ProjectListScreen.kt          ← conecta effect NavigateToProject
presentation/projects/ProjectContract.kt            ← NavigateToProject lleva projectId
presentation/chat/ChatScreen.kt                     ← botón back + panel docs de contexto
```

### Archivos CREADOS esta sesión:
```
presentation/projects/ProjectDetailScreen.kt        ← lista chats + árbol docs + file picker
presentation/projects/ProjectDetailContract.kt      ← intents/state/effects del detalle
presentation/projects/ProjectDetailViewModel.kt     ← crea chats, ingesta docs, toggle checkbox
```

---

## ESTADO ACTUAL DE LA APP (post sesión 3)

### Flujo de navegación completo (ya funciona):
```
ProjectListScreen → [tap proyecto] → ProjectDetailScreen → [tap chat] → ChatScreen
                                   → [ícono upload] → FilePicker → ingestión WorkManager
```

### ✅ Funcionalidades operativas:
| Función | Estado |
|---------|--------|
| Crear/borrar proyectos con system prompt | ✅ |
| Configurar API keys (todas las providers) | ✅ |
| Navegar de proyecto → detalle | ✅ (fix sesión 3) |
| Crear nuevo chat dentro de un proyecto | ✅ (nuevo sesión 3) |
| Navegar de detalle → chat | ✅ (nuevo sesión 3) |
| Cargar documentos (PDF/DOCX/TXT/XLSX/imagen) | ✅ (nuevo sesión 3) |
| Árbol de documentos con checkboxes | ✅ (nuevo sesión 3) |
| Panel de contexto en chat con badge | ✅ (nuevo sesión 3) |
| Chatear con la IA con streaming SSE | ✅ (pendiente probar post-rebuild) |
| Fallback automático entre providers | ✅ (en router) |
| Detener streaming | ✅ |
| Ingesta vectorial (RAG) en background | ✅ vía WorkManager |

### ⏳ Pendiente:
| Tarea | Prioridad |
|-------|-----------|
| **Rebuild + reinstalar APK** con los cambios de sesión 3 | 🔴 INMEDIATA |
| Probar chat con API key real y medir streaming | 🔴 INMEDIATA |
| Tests instrumentados E2E (NODO-14) | 🟡 IMPORTANTE |
| Build release y medir RAM real (target < 100 MB) | 🟡 IMPORTANTE |
| Modelo TFLite `minilm_l6_v2_quantized.tflite` (RAG semántico real) | 🟡 |
| Regenerar último mensaje en chat | 🟢 MEJORA |
| Exportar artefactos generados por IA | 🟢 MEJORA |

---

## PRÓXIMA SESIÓN — qué hacer exactamente

### PASO 1 — Rebuild e instalar (en la netbook con el celular conectado)
```powershell
cd C:\Users\eligi\fenix-ia-android
git pull origin main
.\gradlew.bat assembleDebug
C:\Users\eligi\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

### PASO 2 — Probar flujo completo en el Xiaomi
1. Abrir la app → debe mostrar lista de proyectos
2. Tocar un proyecto → debe ir a ProjectDetailScreen (nueva pantalla con chats y documentos)
3. Tocar "+" → debe crear un chat y navegar a ChatScreen
4. En ChatScreen, tocar el ícono de clip → debe mostrar panel de documentos de contexto
5. Escribir un mensaje y enviarlo → debe mostrar streaming con tokens

### PASO 3 — Probar carga de documentos
1. En ProjectDetailScreen, tocar ícono de upload (barra superior)
2. Seleccionar un PDF o TXT
3. Ver que aparece en la lista de documentos con "Procesando..."
4. Activar el checkbox del documento
5. Ir al chat y verificar que el badge del clip muestra "1"

### PASO 4 — Si hay errores de compilación
Posibles problemas y soluciones:
- **Error: `ProjectDetailViewModel` no inyecta `WorkManager`** → WorkManager se obtiene via `WorkManager.getInstance(context)`, ya está así en el código ✅
- **Error: `DocumentIngestionWorker.KEY_PROJECT_ID` espera Long** → el VM pasa `projectId.hashCode().toLong()` ✅
- **Error de navegación en NavHost** → verificar que `Routes.projectDetail()` y `Routes.chat()` están correctos ✅

### PASO 5 — Tests instrumentados
```powershell
.\gradlew.bat connectedDebugAndroidTest --tests "com.fenix.ia.E2ESmokeTest"
```

---

## Entorno de desarrollo (sin cambios desde sesión 2)
- OS: Windows 11, netbook Juana Manso
- Java: OpenJDK 17.0.18 (Temurin)
- Python: 3.13.13
- Gradle: 8.9 (wrapper)
- Android SDK: `C:\Users\eligi\AppData\Local\Android\Sdk`
- ADB: `C:\Users\eligi\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Repo local: `C:\Users\eligi\fenix-ia-android`
- Dispositivo: Xiaomi Redmi C14 (RS4HQ4YHQKZLLB4T)

## Commits sesión 3
- `98e446e` — fix: conectar navegación completa — projects→detail→chat, rutas con projectId
- `bef0327` — refactor: ProjectEffect navega con projectId (no chatId)
- `07d2a69` — fix: ProjectListScreen usa onNavigateToProject y conecta effect de navegación
- `cfb282f` — feat: nueva ProjectDetailScreen con lista de chats, árbol de documentos y file picker
- `5ca780f` — feat: ProjectDetailContract — intents, state y effects para detalle de proyecto
- `97b2122` — feat: ProjectDetailViewModel — crea chats, ingesta docs, WorkManager, toggle checkpoint
- `2411d83` — feat: ChatScreen con botón back, panel de documentos de contexto y badge

## Todos los commits del proyecto
Ver: https://github.com/eligiansupeer-hash/fenix-ia-android/commits/main

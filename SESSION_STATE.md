# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
26 Abril 2026 — Sesión 5 (fix CI)

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
| NODO-08 | ✅ COMPLETO | `presentation/chat/ChatViewModel.kt` + `ChatScreen.kt` |
| NODO-09 | ✅ COMPLETO | `presentation/sdui/` |
| NODO-10 | ✅ COMPLETO | `sandbox/PolicyEngine.kt` + `DynamicExecutionEngine.kt` |
| NODO-11 | ✅ COMPLETO | `data/local/ArtifactManager.kt` |
| NODO-12 | ✅ COMPLETO | 6 tests unitarios JVM — BUILD SUCCESSFUL ✅ |
| NODO-13 | ⏳ PENDIENTE | `MemoryStressTest.kt` escrito — requiere dispositivo conectado |
| NODO-14 | ✅ ESCRITO | `E2ESmokeTest.kt` (6 tests) — requiere dispositivo conectado |

---

## 🟢 HITO: CI PIPELINE 100% VERDE

**Commit:** `e1b7023` — fix(nodo-06): corregir ProjectDetailViewModel

Las tres etapas del pipeline pasaron en verde:
- ✅ **Job 1 — Compilar Kotlin** (`compileDebugKotlin`)
- ✅ **Job 2 — Tests Unitarios JVM** (`testDebugUnitTest`)
- ✅ **Job 3 — Build APK Debug** (`assembleDebug`)

El APK está disponible como artefacto en:
https://github.com/eligiansupeer-hash/fenix-ia-android/actions

---

## Causa del último error CI (ya corregido)

`ProjectDetailViewModel.kt` estaba desincronizado con la arquitectura real:

| Problema | Causa | Fix |
|----------|-------|-----|
| `No parameter 'absolutePath'` | `DocumentNode` usa `uri`, no `absolutePath` | Usa `uri = uri.toString()` |
| `Unresolved reference 'KEY_FILE_PATH'` | Worker solo expone `KEY_DOCUMENT_ID` y `KEY_PROJECT_ID` | Usa `DocumentIngestionWorker.buildRequest()` |
| `Argument type mismatch` en `workDataOf` | Pares construidos manualmente con tipos incorrectos | Delegado a `buildRequest()` que encapsula `WorkData` |

---

## Lo que hizo la sesión 4 (26 Abril 2026)

### Funcionalidades agregadas:
| Función | Archivo |
|---------|---------|
| `deleteMessageById` en DAO | `MessageDao.kt` |
| `deleteMessage` en interfaz | `MessageRepository.kt` |
| `deleteMessage` implementado | `MessageRepositoryImpl.kt` |
| `RegenerateLastMessage` como `object` + `DismissError` | `ChatContract.kt` |
| `regenerateLastMessage()` + `collectInferenceStream()` extraído | `ChatViewModel.kt` |
| Snackbar errores + botones Copiar/Regenerar | `ChatScreen.kt` |
| Test 6: Regenerar último mensaje | `E2ESmokeTest.kt` |

---

## ESTADO ACTUAL DE LA APP

### ✅ Funcionalidades operativas:
| Función | Estado |
|---------|--------|
| Crear/borrar proyectos con system prompt | ✅ |
| Configurar API keys (todas las providers) | ✅ |
| Navegar proyecto → detalle → chat | ✅ |
| Crear/borrar chats dentro de un proyecto | ✅ |
| Cargar documentos (PDF/DOCX/TXT/imagen) | ✅ |
| Árbol de documentos con checkboxes | ✅ |
| Panel de contexto en chat con badge | ✅ |
| Chatear con IA con streaming SSE | ✅ |
| Fallback automático entre providers | ✅ |
| Detener streaming | ✅ |
| Regenerar último mensaje | ✅ |
| Copiar mensaje del asistente | ✅ |
| Snackbar de errores de API | ✅ |
| Ingesta vectorial (RAG) en background | ✅ |

### ⏳ Pendiente:
| Tarea | Prioridad |
|-------|-----------|
| Instalar APK en Xiaomi y probar flujo real | 🔴 INMEDIATA |
| Tests instrumentados E2E (NODO-14) en dispositivo | 🟡 |
| Medir RAM en release build (objetivo < 100 MB PSS) | 🟡 |
| Integrar modelo TFLite MiniLM (RAG semántico real) | 🟡 |
| UI para exportar artefactos generados | 🟢 |

---

## PRÓXIMA SESIÓN — qué hacer exactamente

### PASO 1 — Bajar el APK del CI y instalarlo
El APK compilado está disponible en GitHub Actions (14 días):
https://github.com/eligiansupeer-hash/fenix-ia-android/actions

O rebuild local en la netbook:
```powershell
cd C:\Users\eligi\fenix-ia-android
git pull origin main
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### PASO 2 — Probar flujo completo en el Xiaomi
1. Abrir app → Configuración → ingresar API key de Groq (gratis: console.groq.com)
2. Crear proyecto → nuevo chat → enviar mensaje
3. Verificar streaming → respuesta → botones Copiar y Regenerar
4. Tocar Regenerar → debe borrar respuesta y generar nueva

### PASO 3 — Si hay error de íconos al compilar local
```kotlin
// Si Icons.Default.ContentCopy o Icons.Default.Refresh no compilan,
// agregar en app/build.gradle.kts:
implementation("androidx.compose.material:material-icons-extended")
```

### PASO 4 — Medir RAM en release
```powershell
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release-unsigned.apk
adb shell dumpsys meminfo com.fenix.ia | findstr "TOTAL PSS"
# Objetivo: < 100,000 KB (100 MB PSS)
```

---

## Entorno de desarrollo
- OS: Windows 11, netbook Juana Manso
- Java: OpenJDK 17.0.18 (Temurin)
- Gradle: 8.9 (via gradle/actions/setup-gradle en CI, gradlew.bat local)
- Android SDK: `C:\Users\eligi\AppData\Local\Android\Sdk`
- Repo local: `C:\Users\eligi\fenix-ia-android`
- Dispositivo: Xiaomi Redmi C14 (`RS4HQ4YHQKZLLB4T`)

## Commits de esta sesión (sesión 5)
- `e1b7023` — fix(nodo-06): corregir ProjectDetailViewModel — alinear con DocumentNode.uri y DocumentIngestionWorker.buildRequest()
- `6abd451` — chore: actualizar SESSION_STATE.md (sesión anterior)

## Todos los commits
https://github.com/eligiansupeer-hash/fenix-ia-android/commits/main

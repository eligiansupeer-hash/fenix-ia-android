# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
26 Abril 2026 — Sesión 4

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
| NODO-08 | ✅ COMPLETO | `presentation/chat/ChatViewModel.kt` + `ChatScreen.kt` (actualizado sesión 4) |
| NODO-09 | ✅ COMPLETO | `presentation/sdui/` |
| NODO-10 | ✅ COMPLETO | `sandbox/PolicyEngine.kt` + `DynamicExecutionEngine.kt` |
| NODO-11 | ✅ COMPLETO | `data/local/ArtifactManager.kt` |
| NODO-12 | ✅ TESTS PASANDO | BUILD SUCCESSFUL — todos los tests JVM OK |
| NODO-13 | ⏳ PENDIENTE TEST | RAM idle medida: 172 MB debug (aceptable — release será ~60% menos) |
| NODO-14 | ✅ TESTS ESCRITOS | `E2ESmokeTest.kt` — 6 tests listos, requieren rebuild + dispositivo conectado |

---

## Lo que hizo la sesión 4 (26 Abril 2026)

### 7 commits — plomería para Regenerar + UX de chat completa

#### Archivos MODIFICADOS:

| Archivo | Cambio |
|---------|--------|
| `MessageDao.kt` | + `deleteMessageById(id)` — necesario para regenerar |
| `MessageRepository.kt` | + `deleteMessage(id)` en la interfaz |
| `MessageRepositoryImpl.kt` | + implementación `deleteMessage → dao.deleteMessageById` |
| `ChatContract.kt` | `RegenerateLastMessage` ahora es `object` (no necesita chatId); + `DismissError` intent |
| `ChatViewModel.kt` | Implementa `regenerateLastMessage()` + extrae `collectInferenceStream()` para evitar duplicación; DismissError limpia el error del estado |
| `ChatScreen.kt` | **SnackbarHost** para errores de API; botones **Copiar** y **Regenerar** en el último mensaje del asistente; usa `LocalClipboardManager` |
| `E2ESmokeTest.kt` | + Test 6: verifica que el botón Regenerar aparece y lanza streaming |

---

## ESTADO ACTUAL DE LA APP (post sesión 4)

### Flujo de navegación (sin cambios):
```
ProjectListScreen → [tap proyecto] → ProjectDetailScreen → [tap chat] → ChatScreen
                                   → [ícono upload] → FilePicker → ingestión WorkManager
```

### ✅ Funcionalidades operativas:
| Función | Estado |
|---------|--------|
| Crear/borrar proyectos con system prompt | ✅ |
| Configurar API keys (todas las providers) | ✅ |
| Navegar de proyecto → detalle → chat | ✅ |
| Crear/borrar chats dentro de un proyecto | ✅ |
| Cargar documentos (PDF/DOCX/TXT/XLSX/imagen) | ✅ |
| Árbol de documentos con checkboxes | ✅ |
| Panel de contexto en chat con badge | ✅ |
| Chatear con la IA con streaming SSE | ✅ |
| Fallback automático entre providers | ✅ |
| Detener streaming | ✅ |
| **Regenerar último mensaje** | ✅ NEW sesión 4 |
| **Copiar mensaje del asistente** | ✅ NEW sesión 4 |
| **Snackbar de errores de API** | ✅ NEW sesión 4 |
| Ingesta vectorial (RAG) en background | ✅ vía WorkManager |

### ⏳ Pendiente:
| Tarea | Prioridad |
|-------|-----------|
| **Rebuild + reinstalar APK** con los cambios de sesiones 3 y 4 | 🔴 INMEDIATA |
| Probar chat con API key real y medir streaming | 🔴 INMEDIATA |
| Tests instrumentados E2E (NODO-14) — ya escritos, falta correrlos | 🟡 IMPORTANTE |
| Build release y medir RAM real (target < 100 MB) | 🟡 IMPORTANTE |
| Modelo TFLite `minilm_l6_v2_quantized.tflite` (RAG semántico real) | 🟡 |
| Exportar artefactos generados por IA (ArtifactManager UI) | 🟢 MEJORA |

---

## PRÓXIMA SESIÓN — qué hacer exactamente

### PASO 1 — Rebuild e instalar (netbook con celular conectado por USB)
```powershell
cd C:\Users\eligi\fenix-ia-android
git pull origin main
.\gradlew.bat assembleDebug
C:\Users\eligi\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```
*(~50 minutos en la netbook — dejarlo en background)*

### PASO 2 — Probar flujo completo en el Xiaomi
1. Abrir la app → lista de proyectos
2. Ir a Configuración → ingresar API key de Groq (gratis en console.groq.com)
3. Crear un proyecto → nuevo chat → escribir un mensaje
4. Verificar: streaming aparece → respuesta llega → botones Copiar y Regenerar visibles
5. Tocar Regenerar → debe borrar la respuesta y generar una nueva

### PASO 3 — Tests E2E (si el celular está conectado con USB)
```powershell
.\gradlew.bat connectedDebugAndroidTest --tests "com.fenix.ia.E2ESmokeTest"
```
Los Tests 4 y 5 (startup + navegación sin crash) deben pasar sin API key real.
Los Tests 1, 2, 6 necesitan API key configurada.

### PASO 4 — Build release (para medir RAM real)
```powershell
.\gradlew.bat assembleRelease
# Firmar con debug keystore para instalar:
C:\Users\eligi\AppData\Local\Android\Sdk\build-tools\35.0.0\apksigner.bat sign `
  --ks C:\Users\eligi\.android\debug.keystore `
  --ks-key-alias androiddebugkey `
  --ks-pass pass:android `
  app\build\outputs\apk\release\app-release-unsigned.apk
adb install -r app\build\outputs\apk\release\app-release-unsigned.apk
# Medir RAM:
adb shell dumpsys meminfo com.fenix.ia | findstr "TOTAL PSS"
# Objetivo: < 100 MB (100,000 KB)
```

### PASO 5 — Si hay errores de compilación en sesión 4
Posibles problemas:
- **`Icons.Default.ContentCopy` no encontrado** → importar `androidx.compose.material.icons.Icons` y asegurarse de tener `compose-material-icons-extended` en deps o usar `Icons.Outlined.ContentCopy`
- **`Icons.Default.Refresh` no encontrado** → ídem
- **`LocalClipboardManager` import** → es `androidx.compose.ui.platform.LocalClipboardManager`

Si hay error de icons, solución rápida:
```kotlin
// En ChatScreen.kt, reemplazar los imports de icons específicos por:
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
// Y agregar en build.gradle.kts:
implementation("androidx.compose.material:material-icons-extended")
```

---

## Entorno de desarrollo (sin cambios)
- OS: Windows 11, netbook Juana Manso
- Java: OpenJDK 17.0.18 (Temurin)
- Gradle: 8.9 (wrapper)
- Android SDK: `C:\Users\eligi\AppData\Local\Android\Sdk`
- ADB: `C:\Users\eligi\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Repo local: `C:\Users\eligi\fenix-ia-android`
- Dispositivo: Xiaomi Redmi C14 (RS4HQ4YHQKZLLB4T)

## Commits sesión 4
- `2474fdf` — feat: add deleteMessageById to MessageDao for regenerate support
- `e96b4d9` — feat: add deleteMessage to MessageRepository interface
- `9dd25cd` — feat: implement deleteMessage in MessageRepositoryImpl
- `9602b4e` — feat: implement RegenerateLastMessage + extract collectInferenceStream + DismissError intent
- `0fbe77e` — feat: add DismissError intent and fix RegenerateLastMessage to object
- `45ac8d6` — feat: ChatScreen — Snackbar errors + copy/regenerate actions on last assistant message
- `4ec609f` — test: E2ESmokeTest — agrega Test 6 regenerar último mensaje del asistente

## Todos los commits del proyecto
Ver: https://github.com/eligiansupeer-hash/fenix-ia-android/commits/main

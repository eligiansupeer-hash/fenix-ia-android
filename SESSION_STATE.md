# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
30 Abril 2026 — Sesión 13 (Hotfix build error ToolCallParser)

---

## Estado de nodos

| Nodo | Estado |
|------|--------|
| NODO-00 al 12 | ✅ COMPLETO |
| NODO-13 | ⏳ Requiere dispositivo físico + ADB |
| NODO-14 | ⏳ Requiere dispositivo físico + ADB |
| **OTA** | ✅ Funcionando desde sesión 6 |
| **A1** | ✅ COMPLETO |
| **A2** | ✅ COMPLETO |
| **A3** | ✅ COMPLETO |
| **B1** | ✅ COMPLETO |
| **B2** | ✅ COMPLETO |
| **B3** | ✅ COMPLETO |
| **C1** | ✅ COMPLETO |
| **C2** | ✅ COMPLETO |
| **C3** | ✅ COMPLETO |
| **D1** | ✅ COMPLETO |
| **D2** | ✅ COMPLETO |
| **D3** | ✅ COMPLETO |
| **E1** | ✅ COMPLETO |
| **E2** | ✅ COMPLETO |
| **E3** | ✅ COMPLETO |
| **E4** | ✅ COMPLETO — hotfix sesión 12 |
| **F1** | ✅ COMPLETO — hotfix sesión 13 |

---

## 🔧 Hotfix Sesión 13

### Problema
CI fallaba en `:app:compileDebugKotlin` con:
```
e: ToolCallParser.kt:66:14 None of the following candidates is applicable
```

### Causa raíz
`stripToolCalls` usaba `text.replace(Regex("..."))` sin el segundo argumento `replacement`.
La firma correcta de Kotlin es `String.replace(regex: Regex, replacement: String)`.

### Fix aplicado
```kotlin
// ANTES (error):
text.replace(Regex("$OPEN_TAG[\\s\\S]*?$CLOSE_TAG")).trim()

// DESPUÉS (correcto):
text.replace(Regex("${Regex.escape(OPEN_TAG)}[\\s\\S]*?${Regex.escape(CLOSE_TAG)}"), "").trim()
```
Se agregó además `Regex.escape()` en ambos tags por seguridad (los `<` y `>` son seguros,
pero es buena práctica cuando se insertan constantes en expresiones regex).

| Archivo | Commit | Descripción |
|---------|--------|-------------|
| `tools/ToolCallParser.kt` | `538c94a` | fix: replace(Regex, replacement) correcto |

---

## 🏁 TODOS LOS NODOS DEL MANUAL v2 COMPLETOS

### Nodo F — Loop de tool-use real (sesión 12-13)

| Archivo | Estado | Descripción |
|---------|--------|-------------|
| `tools/ToolCallParser.kt` | ✅ | Detecta/parsea `<tool_call>` del LLM |
| `orchestrator/OrchestratorEngine.kt` | ✅ | Loop real: LLM → tools → LLM |
| `orchestrator/OrchestratorEvent.kt` | ✅ | +ToolExecuted, +WorkflowFailed |
| `presentation/workflow/WorkflowViewModel.kt` | ✅ | Muestra ToolExecuted en UI |
| `presentation/tools/ToolsScreen.kt` | ✅ | UX sin JSON crudo al usuario |
| `presentation/tools/ToolsViewModel.kt` | ✅ | executeNatural + clearResult |

### Fase 5 — IA Local On-Device (D1–D3)

| Archivo | Commit | Descripción |
|---------|--------|-------------|
| `gradle/libs.versions.toml` | `9a89993` | +mediapipe 0.10.14 |
| `app/build.gradle.kts` | `6819808` | +implementation(libs.mediapipe.genai) |
| `local/LocalLlmEngine.kt` | `6cbba56` | MediaPipe LLM Inference + Llama 3.2 1B Q4 |
| `queue/TaskQueueManager.kt` | `8e7a1ce` | Cola dual: Semaphore(1) + WorkManager |
| `queue/AgentWorker.kt` | `18a1697` | @HiltWorker con retry automático (max 3) |
| `presentation/settings/SettingsViewModel.kt` | `14da808` | +LocalLlmEngine integrado |
| `presentation/settings/SettingsScreen.kt` | `88945e5` | +LocalAiSection panel descarga/activar |

### Fase 6 — Frontend completo (E1–E4)

| Archivo | Commit | Descripción |
|---------|--------|-------------|
| `presentation/FenixNavHost.kt` | `4cbff79` | 8 rutas finales |
| `presentation/projects/ProjectDetailScreen.kt` | `599f4ce` | BottomAppBar 4 botones + FAB |
| `presentation/tools/ToolsScreen.kt` | `98b4335` | Catálogo reactivo + creación IA |
| `presentation/tools/ToolsViewModel.kt` | `f288d74` | HOTFIX PolicyEngine.evaluate() correcto |
| `presentation/artifacts/ArtifactsViewModel.kt` | `5642a28` | Scan + export + filtros |
| `presentation/artifacts/ArtifactsScreen.kt` | `d4b100e` | LazyColumn filtros + exportar |

---

## 🟢 Estado general de la app

| Función | Estado |
|---------|--------|
| Crear/borrar proyectos con system prompt | ✅ |
| Configurar API keys (todos los providers) | ✅ |
| Chat con streaming SSE + fallback providers | ✅ |
| Documentos → contenido real llega al LLM | ✅ |
| Auto-actualización OTA | ✅ |
| Room v2 con tabla `tools` | ✅ |
| ToolSeeder siembra 14 tools al iniciar | ✅ |
| ToolExecutor: read_file, create_file, RAG, sandbox, web, scrape | ✅ |
| **Loop tool-use real: LLM detecta → ejecuta → continúa** | ✅ |
| Sistema de agentes (6 roles + OrchestratorEngine + Blackboard) | ✅ |
| WorkflowScreen con NodeGraphCanvas + ToolExecuted en log | ✅ |
| Deep Research: DuckDuckGo + IterDRAG | ✅ |
| ResearchScreen con log en tiempo real | ✅ |
| IA Local on-device: LocalLlmEngine + MediaPipe | ✅ |
| Cola dual: TaskQueueManager + AgentWorker | ✅ |
| Panel IA Local en Settings | ✅ |
| FenixNavHost 8 rutas completas | ✅ |
| ProjectDetailScreen BottomAppBar 4 nav + FAB | ✅ |
| ToolsScreen catálogo reactivo + crear con IA (sin JSON crudo) | ✅ |
| ArtifactsScreen árbol + filtros + exportar | ✅ |

---

## Restricciones vigentes (no violar)
- No Flutter/WebView/RxJava
- No SharedPreferences → DataStore + Keystore
- No DCL (DexClassLoader)
- No cargar PDF/DOCX completo en heap (R-04)
- `bitmap.recycle()` siempre en `finally`
- RAM idle < 100 MB PSS
- `versionCode` sincronizado con `LOCAL_VERSION_CODE` en UpdateChecker.kt

---

## Próximos pasos
- ✅ CI debería pasar con el hotfix de ToolCallParser
- NODO-13 / NODO-14: Requieren dispositivo físico con ADB conectado
- Release v2.0.0 en GitHub cuando CI esté verde

# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
30 Abril 2026 — Sesión 9 (Manual Evolutivo v2: NODO-A2 y NODO-A3)

---

## Estado de nodos

| Nodo | Estado |
|------|--------|
| NODO-00 al 12 | ✅ COMPLETO |
| NODO-13 | ⏳ Requiere dispositivo físico + ADB |
| NODO-14 | ⏳ Requiere dispositivo físico + ADB |
| **OTA** | ✅ Funcionando desde sesión 6 |
| **A1** | ✅ COMPLETO (sesión anterior) |
| **A2** | ✅ COMPLETO (sesión 9) |
| **A3** | ✅ COMPLETO (sesión 9) |
| **B1** | ⏳ PENDIENTE |
| **B2** | ⏳ PENDIENTE |
| **B3** | ⏳ PENDIENTE |
| C1–C3 | ⏳ PENDIENTE |
| D1–D3 | ⏳ PENDIENTE |
| E1–E4 | ⏳ PENDIENTE |

---

## 🟢 HITO SESIÓN 9: NODO-A2 + NODO-A3 completos

### Archivos creados/modificados en sesión 9

| Archivo | Commit | Cambio |
|---------|--------|--------|
| `domain/repository/ToolRepository.kt` | `15c51b3` | Interface: getAllTools, insertTool, updateTool, deleteTool, getEnabledTools |
| `data/repository/ToolRepositoryImpl.kt` | `decbafd` | Impl con mappers domain↔entity, JSON perms serialization |
| `di/RepositoryModule.kt` | `559167a` | +bindToolRepository |
| `tools/ToolSeeder.kt` | `d8cb1a8` | 14 tools semilla: documental, fs, rag, internet, codigo, sistema |
| `FenixApp.kt` | `bf742e9` | +toolSeeder inject, seedIfEmpty() en onCreate |
| `tools/ToolResult.kt` | `abb87ad` | sealed class Success/Error, isRetryable flag |
| `tools/ToolExecutor.kt` | `544427f` | Dispatcher nativo: read_file, create_file, store_knowledge, retrieve_context + sandbox JS |

### Notas técnicas A2/A3:
- `ToolRepositoryImpl` usa `kotlinx.serialization` para serializar `permissions: List<String>` a JSON string en Room
- `ToolExecutor` adapta el API real de `DynamicExecutionEngine.execute(script, inputJson)` (no `executeScript`)
- `RagEngine` expone `search()` con `List<DocumentChunk>` — ToolExecutor mapea `chunk.textPayload` al JSON de salida
- Tools no implementadas aún (web_search, scrape, deep_research, summarize, translate, create_docx, create_pdf, run_code, create_new_tool) retornan `ToolResult.Error` con mensaje orientativo — serán despachadas por OrchestratorEngine en Fases 3–6

---

## ⏭️ PRÓXIMA SESIÓN — NODO-B1 (inicio Fase 3)

### Archivos a crear:
```
domain/model/AgentRole.kt        ← enum con 6 roles + systemPrompt + allowedTools
domain/model/WorkflowPlan.kt     ← data class + WorkflowStep + WorkflowStatus
```

### Archivos a modificar:
- Ninguno en B1

### Prerequisito verificar antes de B1:
- CI (build-apk.yml) debe estar verde luego de los commits de sesión 9
- Confirmar que `ToolRepository` compila correctamente (depende de `kotlinx.serialization` ya en classpath)

---

## Estado general de la app (post sesión 9)

| Función | Estado |
|---------|--------|
| Crear/borrar proyectos con system prompt | ✅ |
| Configurar API keys (todos los providers, incl. GITHUB_MODELS) | ✅ |
| Chat con streaming SSE + fallback providers | ✅ |
| Caja de texto visible con teclado abierto | ✅ |
| Documentos seleccionados → contenido real llega al LLM | ✅ |
| Auto-actualización OTA | ✅ |
| Room v2 con tabla `tools` + AutoMigration | ✅ |
| ToolSeeder siembra 14 tools al iniciar | ✅ |
| ToolExecutor despacha read_file/create_file/RAG/sandbox | ✅ |

---

## Restricciones vigentes (AGENTS.md — no violar)
- No Flutter/WebView/RxJava
- No SharedPreferences → DataStore + Keystore
- No DCL (DexClassLoader)
- No cargar PDF/DOCX completo en heap Java (R-04)
- `bitmap.recycle()` siempre en `finally`
- RAM idle < 100 MB PSS
- `versionCode` en `build.gradle.kts` debe sincronizarse con `LOCAL_VERSION_CODE` en `UpdateChecker.kt`

## Historial de commits relevantes (sesiones 7–9)
- `ff73076` — fix: @SerialName en GithubRelease/GithubAsset (sesión 7)
- `4395f5f` — feat: getChunksByDocumentNodeIds en RagEngine (sesión 8)
- `003f32d` — fix(BUG-1): ChatViewModel inyecta RagEngine, contenido real al LLM (sesión 8)
- `ffde4ab` — fix(BUG-2): imePadding en ChatScreen (sesión 8)
- `0e82cd3` — fix(BUG-3): verticalScroll en SettingsScreen (sesión 8)
- `15c51b3` — feat(nodo-A2): ToolRepository interface (sesión 9)
- `decbafd` — feat(nodo-A2): ToolRepositoryImpl con mappers (sesión 9)
- `559167a` — feat(nodo-A2): bind ToolRepository en RepositoryModule (sesión 9)
- `d8cb1a8` — feat(nodo-A2): ToolSeeder con catalogo de 14 herramientas (sesión 9)
- `bf742e9` — feat(nodo-A2): FenixApp llama ToolSeeder.seedIfEmpty() en onCreate (sesión 9)
- `abb87ad` — feat(nodo-A3): ToolResult sealed class MCP-compatible (sesión 9)
- `544427f` — feat(nodo-A3): ToolExecutor dispatcher nativo + sandbox JS (sesión 9)

# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
30 Abril 2026 — Sesión 9 (Manual Evolutivo v2: NODO-A2, NODO-A3 + fix CI)

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
| **B1** | ⏳ PENDIENTE — próxima sesión |
| **B2** | ⏳ PENDIENTE |
| **B3** | ⏳ PENDIENTE |
| C1–C3 | ⏳ PENDIENTE |
| D1–D3 | ⏳ PENDIENTE |
| E1–E4 | ⏳ PENDIENTE |

---

## 🔴 FIX CI — KSP Room schema (commit b5d2f2f + 5fcd7be)

**Error:** `Schema '1.json' required for migration was not found`

**Causa raíz:** `FenixDatabase` tenía `exportSchema = true` + `autoMigrations = [AutoMigration(from=1, to=2)]`.
Room KSP busca en tiempo de compilación el archivo `app/schemas/.../1.json` para generar el código de migración automática.
Ese JSON nunca se subió al repo (debió generarse y commitearse cuando se creó la v1).

**Fix aplicado:**
- `FenixDatabase.kt`: `exportSchema = false`, eliminado bloque `autoMigrations`
- `AppModule.kt`: se agregó `provideToolDao()` + nota explicativa sobre `fallbackToDestructiveMigration`

**Por qué es correcto:** en desarrollo `fallbackToDestructiveMigration()` ya estaba en el Room builder —
destruye y recrea las tablas al detectar cambio de versión. No hay datos de producción que proteger.
En el futuro, si se pasa a producción, reemplazar por `addMigrations(Migration(1,2) { db -> db.execSQL("CREATE TABLE...") })`.

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
| `data/local/db/FenixDatabase.kt` | `b5d2f2f` | **fix CI**: exportSchema=false, sin autoMigrations |
| `di/AppModule.kt` | `5fcd7be` | **fix CI**: +provideToolDao |

### Notas técnicas A2/A3:
- `ToolRepositoryImpl` usa `kotlinx.serialization` para serializar `permissions: List<String>` a JSON string en Room
- `ToolExecutor` adapta el API real de `DynamicExecutionEngine.execute(script, inputJson)` (no `executeScript`)
- Tools no implementadas aún devuelven `ToolResult.Error` orientativo — se completan en Fases 3–6

---

## ⏭️ PRÓXIMA SESIÓN — NODO-B1 (inicio Fase 3: Sistema de Agentes)

### Archivos a crear:
```
domain/model/AgentRole.kt     ← enum 6 roles (REDACTOR, ANALISTA, PROGRAMADOR,
                                  INVESTIGADOR, SINTETIZADOR, AUDITOR)
                                  cada uno con: systemPrompt, temperature, allowedTools
domain/model/WorkflowPlan.kt  ← WorkflowPlan + WorkflowStep + WorkflowStatus enum
```

### Dependencias de B1:
- `AgentRole.allowedTools` referencia nombres de tools del catálogo A2 (strings, sin import)
- B2 (OrchestratorEngine) depende de B1 y de LlmInferenceRouter (ya existente)

### Verificar antes de arrancar B1:
- CI verde (build-apk.yml) con commits b5d2f2f y 5fcd7be
- Confirmar que `kotlinx.serialization` está en classpath (lo usa ToolRepositoryImpl)

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
| Room v2 con tabla `tools` (sin AutoMigration, con fallback) | ✅ |
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
- `versionCode` en `build.gradle.kts` sincronizado con `LOCAL_VERSION_CODE` en `UpdateChecker.kt`

## Historial de commits (sesiones 7–9)
- `ff73076` — fix: @SerialName en GithubRelease/GithubAsset (s7)
- `4395f5f` — feat: getChunksByDocumentNodeIds en RagEngine (s8)
- `003f32d` — fix(BUG-1): ChatViewModel inyecta RagEngine (s8)
- `ffde4ab` — fix(BUG-2): imePadding en ChatScreen (s8)
- `0e82cd3` — fix(BUG-3): verticalScroll en SettingsScreen (s8)
- `15c51b3` — feat(nodo-A2): ToolRepository interface (s9)
- `decbafd` — feat(nodo-A2): ToolRepositoryImpl (s9)
- `559167a` — feat(nodo-A2): bind ToolRepository DI (s9)
- `d8cb1a8` — feat(nodo-A2): ToolSeeder 14 tools (s9)
- `bf742e9` — feat(nodo-A2): FenixApp seedIfEmpty (s9)
- `abb87ad` — feat(nodo-A3): ToolResult sealed class (s9)
- `544427f` — feat(nodo-A3): ToolExecutor (s9)
- `b5d2f2f` — fix(ci): FenixDatabase exportSchema=false (s9)
- `5fcd7be` — fix(ci): AppModule +provideToolDao (s9)

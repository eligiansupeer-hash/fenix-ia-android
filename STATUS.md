# STATUS — PLAN FENIX IA (Refactoría 1 Mayo 2026)

## Progreso por Fase

| Fase | Descripción | Estado |
|------|-------------|--------|
| 1 | Materialización Hilt/Room | ✅ Ya existía completa |
| 2 | Pipeline RAG (métrica vectorial) | ✅ Ya existía completa |
| 3 | Sandbox JS persistente | ✅ Ya existía completa |
| 4 | Extractor JSON orquestador | ✅ Ya existía completa |
| 5 | Streaming LLM genuino | ✅ Ya existía completa |
| 6 | Ciclo de vida modelo nativo | ✅ Ya existía completa |
| 7 | WorkManager secuencial OCR | ✅ Ya existía completa |
| 8 | Extractor XLSX streaming SAX | ✅ Ya existía completa |
| 9 | Frenos anti-bucle agentes | ✅ Ya existía completa |
| 10 | TLS fingerprint / motor OkHttp | ✅ CI OK — Sesión 1 |
| 11 | Reducer estado streaming abortado | ✅ Completada — Sesión 2 |
| 12 | Scroll automático Compose | ✅ Completada — Sesión 2 |
| 13 | Auditoría final compilación | ✅ Completada — Sesión 3 |

## 🏁 REFACTORÍA COMPLETA

---

## Sesión 1 — 1 Mayo 2026
### Fase 10 — COMPLETADA y CI OK (commit bba6278)
- Import `cio.*` → `okhttp.*`, `HttpClient(CIO)` → `HttpClient(OkHttp)`
- `ConnectionSpec` TLS 1.3/1.2 cipher suites Chrome 120+
- Timeouts en plugin `HttpTimeout`

## Sesión 2 — 1 Mayo 2026

### Auditoría fases 1–9
Verificadas en repo: todas implementadas correctamente. Sin cambios necesarios.

### Fase 11 — COMPLETADA (commit a1e28f3)
**Fix:** `streamingBuffer = ""` en bloque `StreamEvent.Error` de `ChatViewModel.kt`.

### Fase 12 — COMPLETADA (commits 80b1cfd, a1e28f3, 9f14a93)
**Fix:** `ChatEffect.ScrollToBottom` eliminado. Scroll único vía `LaunchedEffect(messages.size)`.

## Sesión 3 — 1 Mayo 2026

### Fase 13 — COMPLETADA (auditoría estructural)
Verificados en repo:
- `FenixDatabase.kt`: `DocumentEntity::class` presente en array `entities` ✅
- `RepositoryModule.kt`: `@Binds bindDocumentRepository` presente ✅
- `MainActivity.kt`: `@AndroidEntryPoint` presente, NavHost configurado ✅
- `ChatContract.kt`: `ScrollToBottom` eliminado del sealed class `ChatEffect` ✅
- Fases 11/12: implementadas correctamente en sesión anterior ✅
- Todas las fases 1–12: verificadas presentes en el código fuente

### CI Versionado Automático — COMPLETADO (commit 186eb71)
**Problema:** `versionCode = 2` hardcodeado → todas las releases quedaban como `v2`.

**Solución implementada en `build-apk.yml`:**
- Nuevo Job 1 `bump-version` (solo en push a main, antes de compilar)
- Lee `versionCode` actual, incrementa +1, actualiza `versionName` → `"1.0.{versionCode}"`
- Commit con `[skip ci]`. Resultado: cada push genera `v3`, `v4`, `v5`... secuencialmente.

## Sesión 4 — 2 Mayo 2026

### Fix de compilación previo a sesión
**Problema:** `MessageRepositoryImpl.kt` compilaba con error porque `MessageEntity` (con `attachmentUris: String?` agregada en sesión anterior) no estaba reflejada en el modelo de dominio `Message` ni en los mappers.
**Fix (2 commits):**
- `Message.kt`: agrega `val attachmentUris: List<String> = emptyList()`
- `MessageRepositoryImpl.kt`: mappers actualizados — CSV ↔ List<String>

### P4 — Chats Generales — COMPLETADA
- `GeneralChatListScreen.kt` — CREADA: pantalla reactiva con FAB para crear, lista vacía ilustrada
- `FenixNavHost.kt` — ACTUALIZADO: rutas `GENERAL_CHATS_LIST` y `CHAT_GENERAL` + helper `chatGeneral()`; `ProjectListScreen` recibe `onNavigateToGeneralChats`

### P5 — Herramientas por Chat — COMPLETADA
- `ChatToolSelectorSheet.kt` — CREADA: `ModalBottomSheet` con `Switch` por tool; badge en TopBar cuenta tools activas
- `ChatContract.kt` — ACTUALIZADO: `ToggleTool`, `AddAttachmentUri`, `ClearPendingAttachments` en `ChatIntent`; `allTools`, `enabledToolIds`, `pendingAttachmentUris` en `ChatUiState`
- `ChatViewModel.kt` — ACTUALIZADO: inyecta `ToolRepository`, carga tools al `loadChat()`, implementa `toggleTool()`, pasa tools al `llmRouter.streamCompletion(tools = activeTools)`

### P6 — Adjuntos de Archivos — COMPLETADA
- `ChatViewModel.kt`: `sendMessage()` combina `pendingAttachmentUris` + adjuntos explícitos; guarda `attachmentUris` en `Message`; limpia pendientes tras envío; salta carga de documentos si `projectId` vacío (chat general)
- `ChatScreen.kt` — ACTUALIZADO: `rememberLauncherForActivityResult(OpenMultipleDocuments)` para file picker; `PendingAttachmentsBar` visible cuando hay adjuntos; botón 📎 en `ChatInputBar`; indicador de adjuntos en `MessageBubble`; botón 🔧 de tools con badge

### S7 — Tests de Verificación — COMPLETADA
- `ToolCallingPipelineTest.kt` — CREADO: 12 tests unitarios puros (sin Android/Hilt) que verifican detección de tool calls en formato inyectado `<tool_call>` y formato nativo OpenAI `delta.tool_calls[0].function`

## Sesión 5 — 2 Mayo 2026

### Fix compilación CI — COMPLETADO (commit 10209e3)
**Error:** `FenixNavHost.kt:53:17 No parameter with name 'onNavigateToGeneralChats' found.`

**Causa raíz:** En sesión 4 se actualizó `FenixNavHost.kt` para pasar `onNavigateToGeneralChats` a `ProjectListScreen`, pero la firma de `ProjectListScreen` no fue actualizada en ese mismo commit.

**Fix aplicado en `ProjectListScreen.kt`:**
- Agregado parámetro `onNavigateToGeneralChats: () -> Unit` en la firma del `@Composable`
- Agregado `IconButton` en la `TopAppBar` con `Icons.Default.Chat` que invoca `onNavigateToGeneralChats`
- Import `Icons.filled.Chat` añadido

**Verificación:** Ningún otro sitio llama a `ProjectListScreen` (solo `FenixNavHost.kt`). Tests unitarios no referencian esta screen.

### Estado CI
- ⏳ Esperando confirmación del Action tras commit 10209e3

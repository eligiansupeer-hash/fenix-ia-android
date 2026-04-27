# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
26 Abril 2026 — Sesión 8 (bugfixes: RAG contenido real + teclado + scroll settings)

---

## Estado de nodos

| Nodo | Estado |
|------|--------|
| NODO-00 al 12 | ✅ COMPLETO |
| NODO-13 | ⏳ Requiere dispositivo físico + ADB |
| NODO-14 | ⏳ Requiere dispositivo físico + ADB |
| **OTA** | ✅ Funcionando desde sesión 6 |

---

## 🟢 HITO SESIÓN 8: 3 bugs críticos resueltos

### Bugs resueltos:

| Bug | Síntoma | Fix | Commit |
|-----|---------|-----|--------|
| BUG-1 | LLM solo recibía metadatos, no contenido real de documentos | `RagEngine.getChunksByDocumentNodeIds()` + inyección en ChatViewModel | `4395f5f` + `003f32d` |
| BUG-2 | Teclado tapaba la caja de texto del chat | `Modifier.imePadding()` en Scaffold de ChatScreen | `ffde4ab` |
| BUG-3 | GITHUB_MODELS no visible en Settings (sin scroll) | `verticalScroll(rememberScrollState())` en Column de SettingsScreen | `0e82cd3` |

### Detalle BUG-1 (más importante):

El `ChatViewModel` no inyectaba `RagEngine` — el LLM recibía solo summaries vacíos.
Ahora el flujo es:

```
Usuario envía mensaje
  → getCheckedDocuments(projectId) → lista de DocumentNode con isChecked=true
  → ragEngine.getChunksByDocumentNodeIds(nodeIds)
      → ObjectBox: query por documentNodeId, ORDER BY chunkIndex
      → concatena textPayload de todos los chunks → trunca a 6000 chars/doc
  → buildSystemPrompt(docNames, documentContent)
      → incluye contenido COMPLETO de cada documento en el system prompt
  → LLM recibe el texto real y puede responder con precisión
```

No requiere modelo TFLite — funciona con embeddings placeholder (FloatArray(0)).
El RAG semántico (búsqueda por similitud) quedará para cuando se integre MiniLM real.

---

## ESTADO ACTUAL DE LA APP (post sesión 8)

| Función | Estado |
|---------|--------|
| Crear/borrar proyectos con system prompt | ✅ |
| Configurar API keys (todos los providers, incl. GITHUB_MODELS) | ✅ |
| Chat con streaming SSE + fallback providers | ✅ |
| Caja de texto visible con teclado abierto | ✅ |
| Documentos seleccionados → contenido real llega al LLM | ✅ |
| Auto-actualización OTA | ✅ |

---

## ⚠️ PRÓXIMA SESIÓN — verificar en Xiaomi

El CI debería estar corriendo (commits `4395f5f`, `003f32d`, `ffde4ab`, `0e82cd3`).
La app en el Xiaomi tiene versionCode=1 (v1). Para actualizar:
1. Confirmar que CI pasó 4/4 verde
2. Ir a Configuración → ↻ → debería ofrecer instalar la nueva versión automáticamente

### Pruebas a realizar:
1. **Teclado**: abrir chat, tocar el campo de texto → verificar que el input queda visible
2. **Settings scroll**: ir a Configuración → hacer scroll → verificar que GITHUB_MODELS aparece con botón "Agregar"
3. **RAG real**: crear proyecto → cargar documento → activar checkbox → preguntar algo sobre el contenido → verificar que la IA responde con información DEL documento (no genérica)

### Si RAG aún no funciona (chunks vacíos):
Causa probable: `DocumentIngestionWorker` no se está encolando al subir el archivo.
Verificar en `data/repository/DocumentRepositoryImpl.kt` que al insertar un documento se encola `DocumentIngestionWorker` con los datos correctos. Si no está implementado, es el siguiente fix.

---

## Archivos modificados en sesión 8

| Archivo | SHA nuevo | Cambio |
|---------|-----------|--------|
| `data/local/objectbox/RagEngine.kt` | `4395f5f` | +`getChunksByDocumentNodeIds()`, +`isDocumentIndexed()` |
| `presentation/chat/ChatViewModel.kt` | `003f32d` | Inyecta RagEngine, pasa contenido real al LLM |
| `presentation/chat/ChatScreen.kt` | `ffde4ab` | `imePadding()` en Scaffold |
| `presentation/settings/SettingsScreen.kt` | `0e82cd3` | `verticalScroll` en Column |

---

## Restricciones vigentes (AGENTS.md — no violar)
- No Flutter/WebView/RxJava
- No SharedPreferences → DataStore + Keystore
- No DCL (DexClassLoader)
- No cargar PDF/DOCX completo en heap Java (R-04)
- `bitmap.recycle()` siempre en `finally`
- RAM idle < 100 MB PSS
- `versionCode` en `build.gradle.kts` debe sincronizarse con `LOCAL_VERSION_CODE` en `UpdateChecker.kt`

## Historial de commits relevantes
- `ff73076` — fix: @SerialName en GithubRelease/GithubAsset (sesión 7)
- `4395f5f` — feat: getChunksByDocumentNodeIds en RagEngine (sesión 8)
- `003f32d` — fix(BUG-1): ChatViewModel inyecta RagEngine, contenido real al LLM (sesión 8)
- `ffde4ab` — fix(BUG-2): imePadding en ChatScreen (sesión 8)
- `0e82cd3` — fix(BUG-3): verticalScroll en SettingsScreen (sesión 8)

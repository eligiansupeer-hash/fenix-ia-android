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
- Nuevo Job 1 `bump-version` (solo en push a main, antes de compilar):
  - Lee `versionCode` actual de `build.gradle.kts`
  - Incrementa +1
  - Actualiza `versionName` → `"1.0.{versionCode}"`
  - Commit con `[skip ci]` para no disparar ciclo infinito
  - Push a main
- Jobs siguientes hacen `checkout ref: main` para tomar el código bumpeado
- Job `release` lee el versionCode ya actualizado del archivo

**Resultado:** cada push a main genera `v3`, `v4`, `v5`... secuencialmente.

# STATUS — PLAN FENIX IA (Refactoría 1 Mayo 2026)

## Progreso por Fase

| Fase | Descripción | Estado |
|------|-------------|--------|
| 1–13 | Refactoría completa (sesiones 1–5) | ✅ Completadas |
| 14 | Loop agéntico tool calling | ✅ Sesión 7 |

---

## Sesión 7 — 2 Mayo 2026 (pre-sesión 8)
Loop agéntico: `ChatViewModel` inyecta `ToolExecutor`, `runAgenticLoop()`, feedback visual.

---

## Sesión 8 — 2 Mayo 2026 — BUGS CRÍTICOS DE PRODUCCIÓN

### Síntomas reportados por el usuario:
1. **Descarga IA nativa** → error de red (modelo Gemma 2B Q4 ~1.5 GB)
2. **OTA** → detecta actualización pero nunca instala
3. **Herramientas web** → web_search / deep_research no conectan a internet

### Diagnóstico

#### F1 — Descarga IA nativa (error de red)
**Causa raíz:** `LocalLlmEngine` inyectaba el `HttpClient` compartido (`@Singleton` sin qualifier).
Ese cliente tiene `socketTimeoutMillis = 120_000L` a nivel OkHttp. Durante la descarga de 1.5 GB
a baja velocidad, si pasan >120s sin bytes recibidos en el socket (pausas de red, throttling del
servidor), OkHttp cancela la conexión con `SocketTimeoutException`.
El bloque `timeout { requestTimeoutMillis = INFINITE }` de Ktor solo afecta la capa Ktor, no el
`readTimeout` del OkHttpClient subyacente — por eso era insuficiente.

**Fix (commits f0af9ed + 74f0ec1):**
- `AppModule.kt`: nuevo `@Named("download")` HttpClient con `OkHttpClient.readTimeout(0)` y
  `writeTimeout(0)` — timeouts 0 = infinito a nivel OkHttp nativo.
- `LocalLlmEngine.kt`: inyecta `@Named("download") HttpClient` en lugar del cliente compartido.
- Se eliminó el bloque `timeout {}` local (innecesario con el cliente dedicado).

#### F2 — OTA nunca instala
**Causa raíz — Bug 2a:** `UpdateChecker.LOCAL_VERSION_CODE = 2` hardcodeado.
El `versionCode` real en `build.gradle.kts` es `45`, por lo que siempre se detectaba actualización.
Pero el bug que impedía la instalación era el Bug 2b:

**Causa raíz — Bug 2b:** `launchInstaller` hacía `File(Uri.parse(localUri).path)`.
En Android 10+ `DownloadManager.COLUMN_LOCAL_URI` devuelve un URI `content://downloads/...`,
y `Uri.parse("content://...").path` devuelve la porción del path del URI (`/...`), no la ruta
real del filesystem. El `File` resultante no existía → `FileProvider.getUriForFile()` lanzaba
`IllegalArgumentException` silenciosamente → el instalador nunca se abría.

**Fix (commits ece8b6e + 126bc97):**
- `UpdateChecker.kt`:
  - `localVersionCode`: ahora lee del `PackageManager` en runtime (no hardcodeado).
  - `localVersionName`: ídem, desde PackageManager.
  - `launchInstaller`: usa `COLUMN_LOCAL_FILENAME` en lugar de `COLUMN_LOCAL_URI` para obtener
    la ruta real del filesystem. Fallback a `Uri.parse(uri).path` si filename está vacío.
  - Inyecta `@Named("api")` explícitamente (GitHub JSON no necesita timeout infinito).
- `file_provider_paths.xml`:
  - Agregada `<files-path name="fenix_models" path="fenix_models/">` para el modelo IA.
  - Agregada `<files-path name="fenix_internal" path=".">` para documentos generados.
  - `external-files-path` cambiado de `path="Download/"` a `path="."` (cubre toda la raíz).

#### F3 — Web search / deep research sin internet
**Causa raíz — Bug 3a:** `WebResearcher.search()` usaba RESULT_BLOCK regex con
`DOT_MATCHES_ALL` sobre el HTML completo de DDG (~200 KB). En dispositivos con poca RAM
producía backtracking exponencial → timeout de regex → `ToolResult.Error`. En dispositivos
con más RAM, el resultado dependía de la estructura exacta del HTML de DDG que puede cambiar.

**Causa raíz — Bug 3b:** El cliente compartido tenía `socketTimeoutMillis = 120_000L`.
Desde Argentina, DDG HTML puede superar 120s de respuesta bajo carga. Resultado: la
conexión se cortaba antes de recibir el body completo.

**Fix (commit 3054820):**
- `WebResearcher.kt`:
  - **Reemplaza DDG HTML por DDG Instant Answer API JSON** (`api.duckduckgo.com/?format=json`):
    devuelve JSON liviano con `RelatedTopics` — sin regex, sin HTML, sin OOM.
  - Fallback a DDG HTML solo si JSON devuelve 0 resultados: en ese caso usa regex
    `LINK_HREF` acotado por línea (O(n) seguro, no sobre el HTML completo).
  - Inyecta `@Named("api")` explícitamente.
  - `socketTimeoutMillis` del cliente API reducido a 90s (AppModule) — calibrado para DDG.

### Commits de la sesión 8

| Commit | Archivo | Descripción |
|--------|---------|-------------|
| `f0af9ed` | `AppModule.kt` | `@Named("download")` con OkHttp timeouts 0; `@Named("api")` socket 90s |
| `74f0ec1` | `LocalLlmEngine.kt` | Inyecta `@Named("download")` HttpClient |
| `ece8b6e` | `UpdateChecker.kt` | versionCode PackageManager + COLUMN_LOCAL_FILENAME |
| `126bc97` | `file_provider_paths.xml` | files-path modelo IA + external-files-path raíz |
| `3054820` | `WebResearcher.kt` | DDG JSON API + fallback HTML acotado + @Named("api") |

### Checklist post-fix
- [ ] CI verde (compilación + tests)
- [ ] Descarga modelo Gemma completa en dispositivo real (>120s sin error)
- [ ] OTA: instala APK correctamente en Android 10+
- [ ] web_search devuelve resultados desde Argentina
- [ ] deep_research completa iteraciones sin timeout

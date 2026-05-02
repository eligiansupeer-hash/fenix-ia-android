# STATUS — PLAN FENIX IA (Refactoría 1 Mayo 2026)

## Progreso por Fase

| Fase | Descripción | Estado |
|------|-------------|--------|
| 1–13 | Refactoría completa (sesiones 1–5) | ✅ Completadas |
| 14 | Loop agéntico tool calling | ✅ Sesión 7 |

---

## Sesión 7 — 2 Mayo 2026
Loop agéntico: `ChatViewModel` inyecta `ToolExecutor`, `runAgenticLoop()`, feedback visual.

---

## Sesión 8 — 2 Mayo 2026 — BUGS CRÍTICOS DE PRODUCCIÓN

### Síntomas reportados:
1. **Descarga IA nativa** → error de red (modelo Gemma 2B Q4 ~1.5 GB)
2. **OTA** → detecta actualización pero corta descarga en % aleatorio
3. **Herramientas web** → web_search / deep_research sin internet

### Fixes aplicados S8 (ver STATUS anterior para detalle):
- `AppModule.kt`: `@Named("download")` con OkHttp readTimeout=0 + `@Named("api")` socket 90s
- `LocalLlmEngine.kt`: inyecta `@Named("download")`
- `UpdateChecker.kt` (S8): versionCode PackageManager + Ktor con resume Range
- `file_provider_paths.xml`: external-files-path raíz
- `WebResearcher.kt`: DDG JSON API + fallback HTML acotado

---

## Sesión 9 — 2 Mayo 2026 — OTA PERSISTE: MIGRACIÓN A CLOUDFLARE R2

### Diagnóstico definitivo del problema OTA persistente:

**Causa raíz confirmada:**
GitHub Releases genera URLs presignadas hacia `objects.githubusercontent.com` con TTL ~60s.
El fix de S8 (Ktor + Range resume) era correcto en teoría, pero falla porque:

1. **OkHttp no propaga el header `Range` en redirects cross-host.**
   GitHub responde 302 → OkHttp sigue al destino SIN el header Range → descarga desde byte 0
   → `RandomAccessFile.seek(resumeFrom)` sobreescribe incorrectamente → archivo corrupto.

2. **El token en la URL presignada del redirect expira ~60s** después de que
   `checkForUpdate()` obtuvo la URL. Si el usuario tarda en presionar "Descargar",
   o si la descarga dura más de 60s (APK ~15-30MB en conexión lenta desde AR),
   GitHub cierra el stream en un punto aleatorio → porcentajes de corte no deterministas.

**Solución definitiva — Cloudflare R2:**
- APK alojado en R2 bucket `fenix-ia-releases` con URL pública permanente.
- Sin tokens presignados, sin TTL, sin redirects cross-host.
- R2 soporta Range requests nativamente → resume funciona perfectamente.
- Sin rate limits de GitHub API.

### Cambios en esta sesión:

| Commit | Archivo | Descripción |
|--------|---------|-------------|
| `f329b50` | `.github/workflows/build-apk.yml` | Job `release-r2`: sube APK + version.json a R2 via wrangler |
| `1500db4` | `UpdateChecker.kt` | Lee version.json desde R2; descarga desde R2 sin TTL |
| `de1cbdc` | `UpdateViewModel.kt` | Simplificado: R2 no requiere re-fetch de URL |

### Infraestructura R2 creada:
- Bucket: `fenix-ia-releases` (región ENAM, creado 2026-05-02)
- `version.json` inicial subido (placeholder versionCode=0)
- URL pública del bucket: configurar en secret `CF_R2_PUBLIC_URL`

### ⚠️ ACCIÓN REQUERIDA — Secrets en GitHub:
Para que el workflow CI funcione, agregar en:
`https://github.com/eligiansupeer-hash/fenix-ia-android/settings/secrets/actions`

| Secret | Valor |
|--------|-------|
| `CF_ACCOUNT_ID` | ID de cuenta Cloudflare (Settings → Account ID) |
| `CF_API_TOKEN` | Token con permisos R2:Edit |
| `CF_R2_BUCKET` | `fenix-ia-releases` |
| `CF_R2_PUBLIC_URL` | URL pública del bucket (habilitar en R2 → Settings → Public Access) |

### ⚠️ ACCIÓN REQUERIDA — URL pública R2:
En Cloudflare Dashboard → R2 → `fenix-ia-releases` → Settings → Public Access → Enable.
La URL tendrá formato: `https://pub-<hash>.r2.dev`
Actualizar `R2_BASE_URL` en `UpdateChecker.kt` con esa URL.

### Checklist post-deploy:
- [ ] Secrets agregados en GitHub repo settings
- [ ] Public access habilitado en bucket R2
- [ ] `R2_BASE_URL` actualizado en UpdateChecker.kt con URL real
- [ ] Push a main → CI corre → Job release-r2 completa
- [ ] `version.json` en R2 muestra versionCode correcto
- [ ] Descarga OTA completa en dispositivo real (sin cortes en % aleatorio)

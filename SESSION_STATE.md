# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
26 Abril 2026 — Sesión 6 (OTA auto-update)

---

## Estado de nodos

| Nodo | Estado |
|------|--------|
| NODO-00 al 12 | ✅ COMPLETO |
| NODO-13 | ⏳ Requiere dispositivo |
| NODO-14 | ✅ Escrito, requiere dispositivo |
| **OTA** | ✅ NUEVO — sistema de actualización automática |

---

## 🟢 HITO SESIÓN 6: Sistema OTA completo

A partir de ahora **la app se actualiza sola** desde Configuración, sin pasar por la netbook.

### Flujo completo implementado:

```
push a main
  → CI Job 1: compileDebugKotlin
  → CI Job 2: testDebugUnitTest
  → CI Job 3: assembleDebug
  → CI Job 4: gh release create "v{versionCode}" + sube APK como asset
                    ↓
              app en el Xiaomi
              Configuración → "Verificar nueva versión"
                    ↓
              UpdateChecker.checkForUpdate()
              → GET api.github.com/repos/.../releases/latest
              → compara versionCode remoto vs local
              → si remoto > local: muestra dialog con release notes + tamaño
                    ↓
              Usuario toca "Descargar e instalar"
                    ↓
              DownloadManager descarga el APK en background
              (barra de progreso en notificaciones del sistema)
                    ↓
              Instalador del sistema se abre automáticamente
              Usuario confirma → app actualizada ✅
```

### Archivos creados/modificados en sesión 6:

| Archivo | Tipo | Descripción |
|---------|------|-------------|
| `updater/UpdateChecker.kt` | NUEVO | GitHub Releases API + DownloadManager OTA |
| `updater/UpdateViewModel.kt` | NUEVO | MVI state: isChecking / isDownloading / updateAvailable |
| `presentation/settings/SettingsScreen.kt` | MODIFICADO | UpdateSection con dialog, progreso animado, snackbar |
| `AndroidManifest.xml` | MODIFICADO | REQUEST_INSTALL_PACKAGES + FileProvider |
| `res/xml/file_provider_paths.xml` | NUEVO | Autoriza Downloads para FileProvider |
| `.github/workflows/build-apk.yml` | MODIFICADO | Job 4: publica GitHub Release con APK como asset |

### Decisiones técnicas clave:

| Decisión | Motivo |
|----------|--------|
| GitHub Releases API pública, sin token | No requiere autenticación — funciona sin configurar secrets en la app |
| Tag format `v{versionCode}` | Entero simple — fácil de comparar, extraído automáticamente del `build.gradle.kts` en CI |
| DownloadManager del sistema (no OkHttp/Ktor) | Maneja reintentos, barra de progreso en notificaciones, escribe en disco sin pasar por heap JVM — R-04 safe |
| FileProvider con `external-files-path` | Scoped Storage — el instalador del sistema necesita URI autorizado, no ruta absoluta |
| Job 4 solo en push a main, no en PRs | Evita releases de ramas de trabajo |

---

## ESTADO ACTUAL DE LA APP

### ✅ Funcionalidades operativas:
| Función | Estado |
|---------|--------|
| Crear/borrar proyectos con system prompt | ✅ |
| Configurar API keys (todas las providers) | ✅ |
| Navegar proyecto → detalle → chat | ✅ |
| Crear/borrar chats | ✅ |
| Cargar documentos (PDF/DOCX/TXT/imagen) | ✅ |
| Árbol de documentos con checkboxes | ✅ |
| Chat con streaming SSE + fallback providers | ✅ |
| Detener / Regenerar / Copiar mensaje | ✅ |
| Ingesta vectorial RAG en background | ✅ |
| **Auto-actualización OTA desde la app** | ✅ NUEVO |

### ⏳ Pendiente:
| Tarea | Prioridad |
|-------|-----------|
| Verificar que el CI Job 4 crea el Release correctamente | 🔴 INMEDIATA |
| Instalar APK en Xiaomi (bajar de GitHub Releases) y probar OTA | 🔴 INMEDIATA |
| Tests instrumentados E2E y RAM stress en dispositivo | 🟡 |
| Modelo TFLite MiniLM para RAG semántico real | 🟡 |

---

## PRÓXIMA SESIÓN — qué hacer exactamente

### PASO 1 — Verificar que el CI publicó el Release
Ir a: https://github.com/eligiansupeer-hash/fenix-ia-android/releases
Debe aparecer un release `v{versionCode}` con el APK adjunto.

Si el Job 4 falla por permisos:
```
Settings → Actions → General → Workflow permissions
→ seleccionar "Read and write permissions" → Save
```

### PASO 2 — Instalar la primera versión (sin OTA aún)
Bajar el APK del release de GitHub directamente al Xiaomi vía navegador,
o via ADB desde la netbook (una sola vez):
```powershell
git pull origin main
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### PASO 3 — Probar el flujo OTA completo
1. Incrementar `versionCode` en `app/build.gradle.kts` (ej: de 1 a 2)
2. Hacer commit y push a main
3. Esperar que el CI termine (~10 min en GitHub)
4. En el Xiaomi: Configuración → "Verificar nueva versión"
5. Debe aparecer el dialog con "v1 → v2"
6. Tocar "Descargar e instalar" → la app se actualiza sola

### PASO 4 — Probar flujo de chat con API key real
1. Configuración → Groq API key (gratis: console.groq.com)
2. Crear proyecto → nuevo chat → enviar mensaje
3. Verificar streaming → respuesta → botones Copiar y Regenerar

---

## Entorno de desarrollo
- OS: Windows 11, netbook Juana Manso
- Gradle: 8.9 (CI: gradle/actions/setup-gradle | local: gradlew.bat)
- Dispositivo: Xiaomi Redmi C14 (`RS4HQ4YHQKZLLB4T`)
- Repo: https://github.com/eligiansupeer-hash/fenix-ia-android

## Commits sesión 6 (OTA)
- `717454c` — feat(ota): UpdateChecker
- `ef703d2` — feat(ota): UpdateViewModel
- `51f329c` — feat(ota): SettingsScreen con UpdateSection
- `22f27c8` — feat(ota): AndroidManifest — REQUEST_INSTALL_PACKAGES + FileProvider
- `b46d2c0` — feat(ota): file_provider_paths.xml
- `3d51925` — feat(ota): CI Job 4 — publica GitHub Release con APK

# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
26 Abril 2026 — Sesión 2

---

## Estado de nodos

| Nodo | Estado | Archivos clave |
|------|--------|----------------|
| NODO-00 | ✅ COMPLETO | `AGENTS.md` |
| NODO-01 | ✅ COMPLETO | `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml` |
| NODO-02 | ✅ COMPLETO | `domain/model/`, `domain/repository/`, `presentation/chat/ChatContract.kt` |
| NODO-03 | ✅ COMPLETO | `security/SecureApiManager.kt` (AES-256-GCM + Keystore TEE) |
| NODO-04 | ✅ COMPLETO | `data/local/db/` — Room entities, DAOs, FenixDatabase (cascade FK) |
| NODO-05 | ✅ COMPLETO | `data/local/objectbox/` — DocumentChunk, RagEngine, EmbeddingModel, TFLiteEmbeddingModel + FallbackHashEmbeddingModel |
| NODO-06 | ✅ COMPLETO | `ingestion/DocumentIngestionWorker.kt` |
| NODO-07 | ✅ COMPLETO | `data/remote/LlmInferenceRouter.kt` |
| NODO-08 | ✅ COMPLETO | `presentation/chat/ChatViewModel.kt` + `ChatScreen.kt` |
| NODO-09 | ✅ COMPLETO | `presentation/sdui/` |
| NODO-10 | ✅ COMPLETO | `sandbox/PolicyEngine.kt` + `DynamicExecutionEngine.kt` |
| NODO-11 | ✅ COMPLETO | `data/local/ArtifactManager.kt` |
| NODO-12 | ✅ TESTS CORRIENDO Y PASANDO | BUILD SUCCESSFUL — 35 tasks, tests JVM puros OK |
| NODO-13 | ✅ CÓDIGO COMPLETO | `MemoryStressTest.kt` — requiere dispositivo |
| NODO-14 | ✅ CÓDIGO COMPLETO | `E2ESmokeTest.kt` — requiere dispositivo |

---

## Lo que hizo la sesión 2 (26 Abril 2026)

### Problema resuelto: entorno Windows en netbook Juana Manso
La netbook corre **Windows 11** con PowerShell. Se configuró el entorno completo:

1. Faltaban `gradlew.bat` y `gradle/wrapper/gradle-wrapper.properties` → creados y subidos al repo
2. Faltaba `gradle/wrapper/gradle-wrapper.jar` → descargado con curl desde GitHub
3. Faltaba `local.properties` con la ruta del SDK → creado con:
   `sdk.dir=C:/Users/eligi/AppData/Local/Android/Sdk`
4. Bug en `SduiValidatorTest.kt` línea 146: escape inválido de backtick `\`` en string Kotlin → corregido usando concatenación de strings

### Entorno confirmado en la netbook:
- OS: Windows 11
- Java: OpenJDK 17.0.18 (Temurin)
- Python: 3.13.13
- Git: 2.54.0
- Gradle: 8.9 (descargado automáticamente por wrapper)
- Android SDK: `C:\Users\eligi\AppData\Local\Android\Sdk`
- Repo local: `C:\Users\eligi\fenix-ia-android`

### Resultado de tests unitarios:
```
.\gradlew.bat testDebugUnitTest
BUILD SUCCESSFUL in 4m 28s
35 actionable tasks: 8 executed, 27 up-to-date
```
**Todos los tests JVM pasaron.**

### Archivos creados/modificados esta sesión:
```
gradlew.bat                                         ← NUEVO (faltaba para Windows)
gradle/wrapper/gradle-wrapper.properties            ← NUEVO (faltaba)
gradle/wrapper/gradle-wrapper.jar                   ← descargado localmente (no en repo — binario)
app/src/test/java/com/fenix/ia/SduiValidatorTest.kt ← CORREGIDO: backtick escape línea 146
```

---

## ESTADO ACTUAL

### ✅ Completado
- Todo el código (NODOS 00-14) está en el repo
- Tests unitarios JVM corren y pasan en Windows (BUILD SUCCESSFUL)
- Gradle wrapper configurado para Windows

### ⏳ Pendiente (requiere dispositivo Android conectado por USB)
- Tests instrumentados (NODO-13 y NODO-14)
- Medición de RAM idle (target < 100 MB PSS)
- Smoke tests E2E

### ⚠️ Pendiente (para producción, no bloquea QA)
- Modelo TFLite `minilm_l6_v2_quantized.tflite` (~22 MB) NO está en el repo
  → Sin él la app usa `FallbackHashEmbeddingModel` (RAG funciona pero sin semántica real)
  → Ver `app/src/main/assets/ASSET_README.md` para instrucciones de descarga

---

## Próxima sesión — qué hacer exactamente

### EN LA NETBOOK (sin dispositivo):
El script `validate_nodo12.sh` es para Linux/bash — no corre en PowerShell Windows.
En su lugar correr directamente:

```powershell
cd C:\Users\eligi\fenix-ia-android
.\gradlew.bat testDebugUnitTest
```
Ya sabemos que pasa. ✅

### CON DISPOSITIVO ANDROID CONECTADO POR USB:
Habilitar USB Debugging en el teléfono (Ajustes → Acerca del teléfono → tocar 7 veces "Número de compilación" → Opciones de desarrollador → Depuración USB).

```powershell
# Verificar que el dispositivo es reconocido
adb devices

# Instalar APK
.\gradlew.bat installDebug

# Medir RAM idle
adb shell am start -n com.fenix.ia/.presentation.MainActivity
# esperar 10 segundos
adb shell dumpsys meminfo com.fenix.ia | findstr "TOTAL PSS"
# Target: menos de 102400 KB
```

### Si `adb` no se reconoce en PowerShell:
Agregar al PATH:
```powershell
$env:PATH += ";C:\Users\eligi\AppData\Local\Android\Sdk\platform-tools"
```

---

## Archivos de tests

### JVM puros (no requieren dispositivo) — YA PASAN ✅
```
app/src/test/java/com/fenix/ia/
├── RagEngineTest.kt
├── PolicyEngineTest.kt
├── SduiValidatorTest.kt          ← fix de backtick aplicado esta sesión
├── ArtifactGeneratorTest.kt
├── DocumentIngestionWorkerTest.kt
└── LlmInferenceRouterTest.kt
```

### Instrumentados (requieren dispositivo) — PENDIENTES ⏳
```
app/src/androidTest/java/com/fenix/ia/
├── MemoryStressTest.kt
└── E2ESmokeTest.kt
```

---

## Commits de esta sesión
- `afa6451` — chore: add gradle wrapper files
- `e35b65e` — chore: add gradlew.bat for Windows
- `a43f491` — fix(test): corregir escape de backtick en SduiValidatorTest linea 146

## Todos los commits del proyecto
Ver: https://github.com/eligiansupeer-hash/fenix-ia-android/commits/main

# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Última sesión
26 Abril 2026

---

## Estado de nodos

| Nodo | Estado | Archivos clave |
|------|--------|----------------|
| NODO-00 | ✅ COMPLETO | `AGENTS.md` — restricciones + RAM_IDLE_LIMIT |
| NODO-01 | ✅ COMPLETO | `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml` |
| NODO-02 | ✅ COMPLETO | `domain/model/`, `domain/repository/`, `presentation/chat/ChatContract.kt` |
| NODO-03 | ✅ COMPLETO | `security/SecureApiManager.kt` (AES-256-GCM + Keystore TEE) |
| NODO-04 | ✅ COMPLETO | `data/local/db/` — Room entities, DAOs, FenixDatabase (cascade FK) |
| NODO-05 | ✅ COMPLETO | `data/local/objectbox/` — DocumentChunk, RagEngine, EmbeddingModel, TFLiteEmbeddingModel (con fallback), FallbackHashEmbeddingModel |
| NODO-06 | ✅ COMPLETO | `ingestion/DocumentIngestionWorker.kt` (HiltWorker, bitmap.recycle() en finally, retry exponencial) |
| NODO-07 | ✅ COMPLETO | `data/remote/LlmInferenceRouter.kt` (SSE, multi-provider, fallback chain) |
| NODO-08 | ✅ COMPLETO | `presentation/chat/ChatViewModel.kt` + `ChatScreen.kt` (streamingBuffer atómico) |
| NODO-09 | ✅ COMPLETO | `presentation/sdui/` — DynamicUiSchema, SduiValidator, DynamicUiComposer |
| NODO-10 | ✅ COMPLETO | `sandbox/PolicyEngine.kt` + `DynamicExecutionEngine.kt` (JavaScriptSandbox) |
| NODO-11 | ✅ COMPLETO | `data/local/ArtifactManager.kt` (Scoped Storage, allowlist extensiones) |
| NODO-12 | ✅ CÓDIGO COMPLETO | 6 tests unitarios JVM puros en `app/src/test/java/com/fenix/ia/` |
| NODO-13 | ✅ CÓDIGO COMPLETO | `MemoryStressTest.kt` en `androidTest/` — requiere dispositivo para ejecutar |
| NODO-14 | ✅ CÓDIGO COMPLETO | `E2ESmokeTest.kt` en `androidTest/` — requiere dispositivo para ejecutar |

---

## Lo que hizo la sesión más reciente (26 Abril 2026)

### Archivos creados/modificados:
```
scripts/validate_nodo12.sh              ← NUEVO: script bash para compuertas 1-2-4-5 sin ADB
app/src/main/assets/ASSET_README.md    ← NUEVO: instrucciones para modelo TFLite MiniLM
app/src/main/java/.../EmbeddingModel.kt ← MODIFICADO: se agregó FallbackHashEmbeddingModel
app/src/main/java/.../TFLiteEmbeddingModel.kt ← MODIFICADO: fallback graceful si falta .tflite
```

### Fixes aplicados:
- `TFLiteEmbeddingModel` ahora usa `FallbackHashEmbeddingModel` si `assets/minilm_l6_v2_quantized.tflite`
  no existe → el build compila y corre en desarrollo sin el modelo de 22 MB
- Script `validate_nodo12.sh` corre las compuertas 1, 2, 4 y 5 del NODO-12 localmente (sin dispositivo)
  Ejecutar con: `bash scripts/validate_nodo12.sh`

---

## ESTADO ACTUAL: Listo para compilación y QA en dispositivo

### Paso 1 — Compilar (netbook, sin dispositivo)
```bash
cd fenix-ia-android
bash scripts/validate_nodo12.sh
```
Esto ejecuta automáticamente: ktlint, detekt, assembleDebug, auditoría de seguridad y tests unitarios.

### Paso 2 — Tests unitarios solos (si solo quieres eso)
```bash
./gradlew testDebugUnitTest
```
Los 6 tests de NODO-12 son JVM puros — no requieren Android SDK ni dispositivo.

### Paso 3 — QA en dispositivo (Samsung A10 o Xiaomi C14)
```bash
# Conectar por USB y verificar ADB
adb devices

# Instalar y ejecutar tests instrumentados
./gradlew connectedDebugAndroidTest

# Medir RAM idle (NODO-12 compuerta 6 + NODO-13)
adb shell am start -n com.fenix.ia/.presentation.MainActivity
sleep 10
adb shell dumpsys meminfo com.fenix.ia | grep "TOTAL PSS"
# Target: < 102400 KB (100 MB PSS)
```

### Si PSS idle > 100 MB → CHECKLIST ANTI-OOM
```
□ bitmap.recycle() en bloque finally de toda rutina de imagen
□ Workers de WorkManager usando Dispatchers.IO
□ ObjectBox queries con .use { } (auto-close)
□ streamingBuffer reseteado a "" tras StreamEvent.Done
□ Apache POI con FileInputStream + .use { }
□ inSampleSize implementado en extractOcrText
□ Chunks procesados en lotes de 10 máximo
□ JavaScriptSandbox con MAX_HEAP 32 MB configurado
```

---

## Modelo TFLite (necesario para embeddings en producción)

El archivo `minilm_l6_v2_quantized.tflite` (~22 MB) NO está en el repo.
Ver: `app/src/main/assets/ASSET_README.md`

Sin el .tflite, la app usa `FallbackHashEmbeddingModel` automáticamente:
- El RAG funciona pero la similaridad semántica no es real
- Válido para desarrollo y QA de UI
- Para producción: descargar el modelo según ASSET_README.md

---

## Archivos de tests (NODO-12 — JVM puros)

```
app/src/test/java/com/fenix/ia/
├── RagEngineTest.kt               7 casos — chunking 500/1000 tokens, overlap, vacío
├── PolicyEngineTest.kt           14 casos — bloquea eval/fetch/localStorage/DOM
├── SduiValidatorTest.kt          12 casos — depth, schemas válidos, acciones peligrosas
├── ArtifactGeneratorTest.kt      18 casos — extensiones permitidas/bloqueadas
├── DocumentIngestionWorkerTest.kt 11 casos — backoff exponencial, lotes 10
└── LlmInferenceRouterTest.kt     12 casos — selectProvider, fallback chain, null
```

## Archivos de tests instrumentados (NODO-13/14 — requieren dispositivo)

```
app/src/androidTest/java/com/fenix/ia/
├── MemoryStressTest.kt   5 gates: PSS idle, bitmaps, 1000 chunks, 50 buffers, post-estrés
└── E2ESmokeTest.kt       5 tests: proyecto→chat→streaming, keys ocultas, árbol docs
```

---

## Commits de esta sesión
- `4ff4a2e` — feat(nodo12): script validate_nodo12.sh (compuertas sin ADB)
- `4897be3` — docs: ASSET_README.md instrucciones modelo TFLite
- `8b32941` — feat(nodo05): FallbackHashEmbeddingModel en EmbeddingModel.kt
- `ed18462` — fix(nodo05): TFLiteEmbeddingModel con fallback graceful sin .tflite

---

## Próxima sesión: qué hacer

**PRIORIDAD 1 — En la netbook (sin dispositivo):**
```bash
bash scripts/validate_nodo12.sh
```
Si alguna compuerta falla → reportar qué falla exactamente en el chat.

**PRIORIDAD 2 — Con dispositivo conectado:**
```bash
./gradlew connectedDebugAndroidTest 2>&1 | tee test_results.log
adb shell dumpsys meminfo com.fenix.ia | grep "TOTAL PSS"
```

**Si el build falla con error de compilación:**
- Pegar el error exacto en el chat
- Los errores más probables son: import faltante en algún DAO, MyObjectBox no generado (requiere primera compilación con plugin ObjectBox activo), o TFLite class not found

**Si los tests unitarios fallan:**
- Ver qué test falla y con qué excepción
- Los 6 tests son autocontenidos (sin mocks externos) — si fallan es por lógica interna

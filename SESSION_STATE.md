# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Fecha de última sesión
Abril 2026

## Nodos completados

| Nodo | Estado | Archivos clave |
|------|--------|----------------|
| NODO-00 | ✅ COMPLETO | `AGENTS.md` con todas las restricciones |
| NODO-01 | ✅ COMPLETO | Scaffold Gradle + AndroidManifest + Theme + NavHost |
| NODO-02 | ✅ COMPLETO | Domain models + Repository interfaces + MVI contracts + ViewModels |
| NODO-03 | ✅ COMPLETO | `SecureApiManager.kt` (AES-256-GCM / Android Keystore TEE) |
| NODO-04 | ✅ COMPLETO | Room entities + DAOs + `FenixDatabase.kt` (cascade FK) |
| NODO-05 | ✅ COMPLETO | `DocumentNode.kt`, `DocumentEntity.kt`, `DocumentRepositoryImpl.kt`, `AppModule.kt` (ObjectBox + EmbeddingModule), `RepositoryModule.kt` |
| NODO-06 | ✅ COMPLETO | `PdfTextExtractor.kt` (bitmap.recycle() en finally — R-06), `DocxTextExtractor.kt` (Apache POI streaming), `DocumentIngestionWorker.kt` (HiltWorker + retry exponencial), `DocumentDao.kt`, `IngestionModule.kt` |
| NODO-07 | ✅ COMPLETO | `LlmInferenceRouter.kt` (SSE streaming, multi-provider, fallback) |
| NODO-08 | ✅ COMPLETO | `ChatViewModel.kt` (MVI + StateFlow, streamingBuffer atómico) + `ChatScreen.kt` |
| NODO-09 | ✅ COMPLETO | `DynamicUiSchema.kt` (sealed class serializable), `SduiValidator.kt` (depth/count/action policy), `DynamicUiComposer.kt` (Compose nativo — cero WebView) |
| NODO-10 | ✅ COMPLETO | `PolicyEngine.kt` (bloquea eval/fetch/import/localStorage/require), `DynamicExecutionEngine.kt` (JavaScriptSandbox aislado desechable — R-05) |
| NODO-11 | ✅ COMPLETO | `ArtifactManager.kt` (Scoped Storage MediaStore, allowlist de extensiones) |
| NODO-12 | ✅ COMPLETO | 6 tests unitarios committeados en `app/src/test/java/com/fenix/ia/` |
| NODO-13 | ✅ COMPLETO | `MemoryStressTest.kt` en `app/src/androidTest/java/com/fenix/ia/` |
| NODO-14 | ✅ COMPLETO | `E2ESmokeTest.kt` en `app/src/androidTest/java/com/fenix/ia/` |

## Archivos creados en esta sesión (NODO-12 a NODO-14)

```
app/src/test/java/com/fenix/ia/
├── RagEngineTest.kt               ← chunking valores límite (500/1000, overlap 50-100)
├── PolicyEngineTest.kt            ← bloquea eval/fetch, permite matemáticas
├── SduiValidatorTest.kt           ← rechaza depth>5, acciones con chars especiales
├── ArtifactGeneratorTest.kt       ← rechaza .apk/.exe, acepta .md/.json
├── DocumentIngestionWorkerTest.kt ← retry exponencial, lotes de 10 chunks
└── LlmInferenceRouterTest.kt      ← selectProvider, fallback, orden de prioridades

app/src/androidTest/java/com/fenix/ia/
├── MemoryStressTest.kt            ← 5 gates: RAM idle, bitmaps, chunks, streaming, post-estrés
└── E2ESmokeTest.kt                ← 5 tests E2E: proyecto→chat→streaming, keys, navegación
```

## Estado de los tests unitarios (NODO-12)

| Test | Archivo | Casos cubiertos |
|------|---------|-----------------|
| RagEngineTest | `RagEngineTest.kt` | 7 casos: chunkSize 500/1000, overlap 75, vacío, texto corto, excepciones |
| PolicyEngineTest | `PolicyEngineTest.kt` | 14 casos: eval, Function, fetch, XMLHttpRequest, localStorage, DOM, rm-rf, scripts legítimos |
| SduiValidatorTest | `SduiValidatorTest.kt` | 12 casos: depth 3/5/6, schemas válidos, acciones con `'";|>\`` prohibidos |
| ArtifactGeneratorTest | `ArtifactGeneratorTest.kt` | 18 casos: rechaza .apk/.exe/.dex/.jar/.bat/.sh, acepta .md/.json/.txt/.html/.kt/.py/.pdf |
| DocumentIngestionWorkerTest | `DocumentIngestionWorkerTest.kt` | 11 casos: backoff 0/1/2/3, retry, max retries, lotes 25/7/0/10 chunks |
| LlmInferenceRouterTest | `LlmInferenceRouterTest.kt` | 12 casos: selección Gemini, fallbacks Groq→Mistral→OpenRouter→GitHub, null, disabled |

## Estado de los tests instrumentados (NODO-13 y NODO-14)

| Test | Archivo | Requiere |
|------|---------|---------|
| MemoryStressTest | `androidTest/MemoryStressTest.kt` | Dispositivo/emulador — mide PSS real |
| E2ESmokeTest | `androidTest/E2ESmokeTest.kt` | Dispositivo/emulador — flujos UI completos |

## Comandos para ejecutar los tests

```bash
# Tests unitarios (no requieren dispositivo)
./gradlew testDebugUnitTest

# Tests con cobertura (compuerta 3 del NODO-12)
./gradlew testDebugUnitTest jacocoTestReport

# Tests instrumentados (requieren dispositivo/emulador)
./gradlew connectedDebugAndroidTest --tests "com.fenix.ia.MemoryStressTest"
./gradlew connectedDebugAndroidTest --tests "com.fenix.ia.E2ESmokeTest"

# Ensamblado final
./gradlew assembleDebug

# Auditoría de seguridad (NODO-12, Compuerta 5)
grep -r "sk-\|AIza\|gsk_\|Bearer \|api_key\s*=\s*\"" app/src/main/java/ --include="*.kt" \
  && echo "FAIL: API KEY EN TEXTO PLANO" || echo "PASS: Sin API keys hardcodeadas"
```

## Estado del proyecto: LISTO PARA QA EN DISPOSITIVO

Todos los nodos de implementación (00-14) están completos en el repositorio.
El siguiente paso es ejecutar los tests instrumentados en dispositivo físico:

1. Conectar Samsung A10 o Xiaomi C14 via ADB
2. Ejecutar `./gradlew assembleDebug` para compilar
3. Ejecutar `./gradlew connectedDebugAndroidTest` para todos los tests
4. Verificar PSS con `adb shell dumpsys meminfo com.fenix.ia | grep "TOTAL PSS"`
5. Si PSS idle > 100 MB → revisar CHECKLIST ANTI-OOM del manual (sección final)

## Notas técnicas importantes

- Los tests unitarios (NODO-12) NO dependen de Android SDK ni Hilt — son JUnit5 puro
- Los tests instrumentados (NODO-13/14) requieren `@RunWith(AndroidJUnit4::class)`
- `MemoryStressTest` usa `Debug.getMemoryInfo()` — más preciso que `Runtime.freeMemory()`
- `E2ESmokeTest` usa tags de accesibilidad (`testTag` en Compose) — asegurarse de que los Composables los tienen definidos
- El timeout de streaming en E2ESmokeTest es 30 segundos — suficiente para API real sin mock

# AGENTS.md — Directivas Operativas de FENIX IA

## Lenguaje y Framework
- UI_FRAMEWORK: Jetpack Compose (declarativo). PROHIBIDO XML layouts para vistas.
- LANGUAGE: Kotlin puro. PROHIBIDO Dart, JavaScript en la capa de UI nativa.
- MIN_SDK: 26 | TARGET_SDK: 35

## Arquitectura
- PATTERN: Clean Architecture + MVI (Model-View-Intent)
- DI: Hilt/Dagger2
- ASYNC: Kotlin Coroutines + StateFlow. PROHIBIDO RxJava.
- THREADING: Toda operación de DB, HTTP, parseo → Dispatcher.IO o Dispatcher.Default.
  PROHIBIDO bloquear el Main Thread.

## Almacenamiento
- METADATA_DB: Room (SQLite). PROHIBIDO Realm, ObjectBox para metadatos relacionales.
- VECTOR_DB: ObjectBox 4.0 con @HnswIndex. PROHIBIDO Pinecone, Weaviate (requieren cloud).
- SECURE_STORAGE: Jetpack DataStore + Android Keystore (AES-256-GCM/TEE).
  PROHIBIDO SharedPreferences, EncryptedSharedPreferences.

## Seguridad
- API_KEYS: Cifradas en hardware TEE via Android Keystore. NUNCA en texto plano.
- JS_EXECUTION: Exclusivamente mediante androidx.javascriptengine:javascriptengine.
  PROHIBIDO DexClassLoader, PathClassLoader para código generado dinámicamente.
- POLICY: Todo script JS evaluado debe pasar PolicyEngine antes de persistirse.

## Red
- HTTP_CLIENT: Ktor con engine OkHttp (migrado en Fase 10 — TLS fingerprint Chrome).
  PROHIBIDO revertir a CIO. PROHIBIDO OkHttp directo como cliente primario sin Ktor.
- STREAMING: Server-Sent Events (SSE) con Accept: text/event-stream.
- FALLBACK_ORDER: Gemini → Groq → Mistral → OpenRouter → GitHub Models.

## Memoria
- RAM_IDLE_LIMIT: 100 MB (PSS medido con dumpsys meminfo).
- BITMAP_POLICY: bitmap.recycle() OBLIGATORIO en bloque finally de toda rutina de OCR/render.
- CHUNK_SIZE: 500-1000 tokens por fragmento, superposición de 50-100 tokens.

## Código Dinámico
- DYNAMIC_LOGIC: JavaScript ES6 almacenado como string en SQLite, ejecutado en JavaScriptSandbox.
- DYNAMIC_UI: JSON Schema 7 validado → DynamicUiComposer Composable.
- MAX_JS_HEAP_PER_ISOLATE: 32 MB (configurable mediante IsolateStartupParameters).

## Tests
- COVERAGE_MIN: 85% de cobertura de rama (JUnit5 + MockK).
- CI_COMMAND_UNIT: ./gradlew testDebugUnitTest
- CI_COMMAND_BUILD: ./gradlew assembleDebug
- LINT_TOOLS: ktlint + detekt (cero advertencias estructurales para merge).
- MAX_RETRY_ON_FAIL: 5 iteraciones. Superado el límite → HALT + notificación.

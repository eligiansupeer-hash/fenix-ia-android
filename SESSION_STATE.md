# FENIX IA Android — Estado de Sesión

## Repositorio
https://github.com/eligiansupeer-hash/fenix-ia-android

## Fecha de última sesión
Abril 2026

## Nodos completados

| Nodo | Estado | Commit |
|------|--------|--------|
| NODO-00 | ✅ COMPLETO | AGENTS.md con todas las restricciones |
| NODO-01 | ✅ COMPLETO | Scaffold Gradle + AndroidManifest + Theme + NavHost |
| NODO-02 | ✅ COMPLETO | Domain models + Repository interfaces + MVI contracts + ViewModels |
| NODO-03 | ✅ COMPLETO | SecureApiManager (AES-256-GCM/Android Keystore TEE) |
| NODO-04 | ✅ COMPLETO | Room entities + DAOs + FenixDatabase (cascade FK) |
| NODO-07 | ✅ COMPLETO | LlmInferenceRouter (SSE streaming, multi-provider, fallback) |
| NODO-08 | ✅ COMPLETO | ChatViewModel (MVI+StateFlow, streamingBuffer atómico) + ChatScreen |

## Nodos pendientes para próxima sesión

| Nodo | Descripción | Prioridad |
|------|-------------|-----------|
| NODO-05 | Motor Vectorial RAG: ObjectBox 4.0 + @HnswIndex + chunking | ALTA |
| NODO-06 | Motor de Ingesta Documental: PDF/DOCX/OCR + WorkManager + bitmap.recycle() | ALTA |
| NODO-09 | UI Dinámica (SDUI/Server-Driven UI): JSON Schema 7 + DynamicUiComposer | MEDIA |
| NODO-10 | Motor de Automodificación: JavaScriptSandbox + PolicyEngine | MEDIA |
| NODO-11 | Generador de Artefactos y Scoped Storage | MEDIA |
| NODO-12 | Pipeline de Calidad: 6 Compuertas de Validación | ALTA |
| NODO-13 | Test de Estrés de Memoria (Hardware Stress Gate) | ALTA |
| NODO-14 | Integración Final y Smoke Tests E2E | ALTA |

## Estructura de archivos creados

```
fenix-ia-android/
├── AGENTS.md                        ← Contrato del proyecto
├── SESSION_STATE.md                 ← Este archivo
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── .gitignore
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/java/com/fenix/ia/
        ├── FenixApp.kt              ← @HiltAndroidApp + WorkManager
        ├── di/
        │   ├── AppModule.kt         ← Room, Ktor, DAOs providers
        │   └── RepositoryModule.kt  ← Bind interfaces → impls
        ├── domain/
        │   ├── model/               ← Project, Chat, Message, DocumentNode, ApiProvider
        │   ├── repository/          ← Interfaces puras (sin Android)
        │   └── usecase/             ← CreateProjectUseCase, CreateChatUseCase
        ├── data/
        │   ├── local/
        │   │   ├── db/              ← FenixDatabase, Entities, DAOs
        │   │   └── security/        ← SecureApiManager
        │   ├── remote/              ← LlmInferenceRouter, StreamEvent
        │   └── repository/          ← Implementaciones (ProjectRepoImpl, etc.)
        └── presentation/
            ├── FenixNavHost.kt
            ├── MainActivity.kt
            ├── theme/Theme.kt
            ├── chat/                ← ChatContract, ChatViewModel, ChatScreen
            ├── projects/            ← ProjectContract, ProjectViewModel, ProjectListScreen
            └── settings/            ← SettingsViewModel, SettingsScreen
```

## Tareas pendientes de NODO-05 (ObjectBox RAG)

1. Agregar plugin `io.objectbox` en `build.gradle.kts` (descomentar línea existente)
2. Agregar `objectbox-android` y `objectbox-kotlin` dependencies
3. Crear `DocumentChunk.kt` con `@Entity` ObjectBox y `@HnswIndex`
4. Implementar `RagEngine.kt` con `indexDocument()`, `search()`, `chunkText()`
5. Crear `EmbeddingModel` interface + `TFLiteEmbeddingModel` (MiniLM ~22MB)
6. Agregar modelo TFLite a `assets/minilm_l6_v2_quantized.tflite`
7. Binding Hilt para RagEngine y EmbeddingModel

## Notas para continuar

- El `DocumentRepositoryImpl` está pendiente (necesita ObjectBox)
- El `ChatViewModel` tiene `ragEngine` como dependencia comentada (TODO)
- `FenixNavHost.kt` tiene ChatScreen comentado (TODO: descomenta cuando NODO-05 esté listo)
- Todos los tests unitarios de NODO-07 pasan (ver LlmInferenceRouterTest.kt)
- El `@HiltAndroidApp` en FenixApp.kt está listo para compilar con KSP

# OPENCODE — Instrucciones para FENIX IA Android

Eres un agente de construcción de software Android. Tu tarea es continuar la implementación
de FENIX IA según el manual `FENIX_IA_MANUAL_AI_EJECUTOR.md` y el estado actual en `SESSION_STATE.md`.

## Contexto del proyecto

- **App:** FENIX IA — asistente IA offline/online para Android
- **Stack:** Kotlin + Jetpack Compose + Hilt + Room + ObjectBox + Ktor + JavaScriptSandbox
- **Restricción crítica:** RAM idle < 100 MB en Samsung A10 (2 GB RAM)
- **Repo:** https://github.com/eligiansupeer-hash/fenix-ia-android

## Estado actual (leer SESSION_STATE.md para detalle)

NODO-00 a NODO-14: código completo y commiteado.
**Tarea inmediata:** Verificar que el proyecto compila y los tests pasan.

## Lo que debes hacer PRIMERO

### 1. Validar que el proyecto compila

```bash
./gradlew assembleDebug
```

Si falla, leer el error y corregir. Los errores más comunes:
- `MyObjectBox not found` → el plugin ObjectBox genera esta clase en la primera compilación.
  Solución: `./gradlew kspDebugKotlin` primero, luego `assembleDebug`
- `Cannot find symbol TFLiteEmbeddingModel` → revisar imports en AppModule.kt
- Errores de Room → revisar que todos los DAOs estén en FenixDatabase.kt

### 2. Ejecutar tests unitarios

```bash
./gradlew testDebugUnitTest
```

Deben pasar los 6 archivos de test en `app/src/test/java/com/fenix/ia/`:
- RagEngineTest (7 casos)
- PolicyEngineTest (14 casos)
- SduiValidatorTest (12 casos)
- ArtifactGeneratorTest (18 casos)
- DocumentIngestionWorkerTest (11 casos)
- LlmInferenceRouterTest (12 casos)

### 3. Script de validación completo

```bash
bash scripts/validate_nodo12.sh
```

## Si hay errores de compilación — qué buscar

### Error: "Unresolved reference: MyObjectBox"
```kotlin
// ObjectBox genera MyObjectBox automáticamente con el plugin.
// Ejecutar esto primero:
// ./gradlew kspDebugKotlin
// Luego compilar normalmente
```

### Error: imports faltantes en DAOs
Verificar que `FenixDatabase.kt` tiene todos los DAOs:
```kotlin
abstract fun projectDao(): ProjectDao
abstract fun chatDao(): ChatDao
abstract fun messageDao(): MessageDao
abstract fun documentDao(): DocumentDao
```

### Error: "Cannot access class TFLiteEmbeddingModel"
Verificar que existe `app/src/main/java/com/fenix/ia/data/local/objectbox/TFLiteEmbeddingModel.kt`

## Estructura esperada del proyecto

```
app/src/main/java/com/fenix/ia/
├── FenixApp.kt                     ← @HiltAndroidApp
├── di/
│   ├── AppModule.kt                ← Room, Ktor, ObjectBox providers
│   └── RepositoryModule.kt         ← @Binds para interfaces → implementaciones
├── domain/
│   ├── model/                      ← Project, Chat, Message, DocumentNode, ApiProvider
│   └── repository/                 ← Interfaces puras (sin imports Android)
├── data/
│   ├── local/
│   │   ├── db/                     ← FenixDatabase, entities/, dao/
│   │   ├── objectbox/              ← DocumentChunk, RagEngine, EmbeddingModel, TFLiteEmbeddingModel
│   │   └── ArtifactManager.kt
│   ├── remote/
│   │   └── LlmInferenceRouter.kt
│   └── repository/                 ← Implementaciones de interfaces domain
├── presentation/
│   ├── MainActivity.kt
│   ├── NavHost.kt
│   ├── chat/                       ← ChatViewModel, ChatScreen, ChatContract
│   ├── projects/                   ← ProjectsViewModel, ProjectsScreen
│   ├── settings/                   ← SettingsViewModel, SettingsScreen
│   └── sdui/                       ← DynamicUiComposer, SduiValidator
├── sandbox/
│   ├── PolicyEngine.kt
│   └── DynamicExecutionEngine.kt
├── ingestion/
│   └── DocumentIngestionWorker.kt
└── security/
    └── SecureApiManager.kt
```

## Restricciones ABSOLUTAS (nunca violar)

| R-01 | PROHIBIDO Flutter, React Native, WebView como UI principal |
| R-02 | PROHIBIDO SharedPreferences / EncryptedSharedPreferences |
| R-03 | PROHIBIDO DexClassLoader / PathClassLoader |
| R-04 | PROHIBIDO cargar PDF/DOCX completos en heap Java |
| R-05 | PROHIBIDO concatenar datos de usuario en strings de scripts JS |
| R-06 | PROHIBIDO omitir bitmap.recycle() en bloques finally de OCR |
| R-07 | PROHIBIDO avanzar al siguiente nodo si hay tests fallidos |

## Flujo de trabajo para cada corrección

1. Identificar el error exacto
2. Buscar el archivo afectado
3. Aplicar la corrección mínima
4. Recompilar y verificar que el error desapareció
5. Ejecutar tests para confirmar que no se rompió nada
6. Commit con mensaje descriptivo

## Después de compilar exitosamente

Si hay un dispositivo Android conectado (ADB):
```bash
adb devices
./gradlew connectedDebugAndroidTest
adb shell dumpsys meminfo com.fenix.ia | grep "TOTAL PSS"
```
Target: PSS < 102400 KB (100 MB)

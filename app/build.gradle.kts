plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization) // @Serializable en DynamicUiSchema.kt
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.objectbox) // ObjectBox 4.0 — genera MyObjectBox + inyecta objectbox-android
}

android {
    namespace = "com.fenix.ia"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fenix.ia"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitHandler"
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

// ── ObjectBox + KAPT nota ─────────────────────────────────────────────────────
// ObjectBox 4.0 NO soporta KSP — solo usa kapt/annotationProcessor internamente.
// El plugin io.objectbox registra su propio procesador KAPT automáticamente.
// El workaround afterEvaluate + languageVersion=1.9 fue removido porque no tuvo
// efecto en tiempo de ejecución: kaptGenerateStubsDebugKotlin sí corría y
// generaba `distanceType = null` en el stub Java igual.
//
// Solución definitiva: en DocumentChunk.kt se omite distanceType en @HnswIndex
// para evitar que KAPT tenga que resolver VectorDistanceType.COSINE.
// Ver: app/src/main/java/com/fenix/ia/data/local/objectbox/DocumentChunk.kt
// ─────────────────────────────────────────────────────────────────────────────

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)

    // Arquitectura
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    // Coroutines
    implementation(libs.coroutines.android)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore + Keystore access
    implementation(libs.datastore.preferences)

    // Ktor HTTP Client
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.logging)

    // Serializacion Kotlin
    implementation(libs.kotlinx.serialization.json)

    // WorkManager (ingesta asincrona)
    implementation(libs.work.runtime.ktx)

    // JavaScriptSandbox
    implementation(libs.javascriptengine)

    // TensorFlow Lite — modelo MiniLM-L6-v2 (384-dim embeddings para RAG)
    // NOTA: requiere assets/minilm_l6_v2_quantized.tflite (~22 MB) — ver ASSET_README.md
    implementation(libs.tensorflow.lite)

    // ObjectBox — runtime explicito como fallback si el plugin no lo inyecta
    implementation(libs.objectbox.android)

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------
    testImplementation(libs.junit5)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

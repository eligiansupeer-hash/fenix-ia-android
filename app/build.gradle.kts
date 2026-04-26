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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    // CRÍTICO: Proguard para reducir APK y evitar reflexión innecesaria
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
    // NODO-12: Habilita JUnit Platform para correr JUnit 5 (Jupiter) y JUnit 4 (Vintage)
    // Los 6 tests top-level usan JUnit 4 (org.junit.*); los subdirectory usan JUnit 5
    // junit-vintage-engine permite que ambos coexistan bajo el mismo runner
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

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

    // Serialización Kotlin
    implementation(libs.kotlinx.serialization.json)

    // WorkManager (ingesta asíncrona)
    implementation(libs.work.runtime.ktx)

    // JavaScriptSandbox
    implementation(libs.javascriptengine)

    // TensorFlow Lite — modelo MiniLM-L6-v2 (384-dim embeddings para RAG)
    // NOTA: requiere assets/minilm_l6_v2_quantized.tflite (~22 MB) — ver ASSET_README.md
    implementation(libs.tensorflow.lite)

    // ObjectBox — runtime explícito como fallback si el plugin no lo inyecta
    implementation(libs.objectbox.android)

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------
    // JUnit 5 Jupiter API: para tests en domain/usecase y data/remote (JUnit 5)
    testImplementation(libs.junit5)
    // JUnit 4: para los 6 tests de NODO-12 top-level (org.junit.Test, org.junit.Assert.*)
    testImplementation(libs.junit4)
    // Vintage engine: permite ejecutar JUnit 4 bajo el JUnit Platform (useJUnitPlatform())
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

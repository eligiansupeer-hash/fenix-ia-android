plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.objectbox)
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Apache POI incluye archivos de licencia duplicados
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

// ── ObjectBox + KAPT nota ─────────────────────────────────────────────────────
// ObjectBox 4.0 NO soporta KSP — usa kapt internamente via su plugin.
// Fix: en DocumentChunk.kt se omitió distanceType en @HnswIndex para evitar
// que KAPT falle resolviendo VectorDistanceType.COSINE en Kotlin 2.0+.
// ─────────────────────────────────────────────────────────────────────────────

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Material Icons Extended — requerido para Icons.Default.Stop, Delete, etc.
    implementation(libs.compose.material.icons.extended)
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
    // kotlinx-coroutines-guava: requerido para ListenableFuture.await()
    // usado en JavaScriptSandbox.createConnectedInstanceAsync().await()
    implementation(libs.coroutines.guava)

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

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // JavaScriptSandbox
    implementation(libs.javascriptengine)

    // TensorFlow Lite
    implementation(libs.tensorflow.lite)

    // ObjectBox runtime
    implementation(libs.objectbox.android)

    // Apache POI — extracción de texto DOCX (R-04: excluye xmlbeans pesado)
    implementation(libs.apache.poi.ooxml) {
        exclude(group = "org.apache.xmlbeans")
        exclude(group = "com.github.virtuald")
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "org.apache.commons", module = "commons-compress")
    }
    // commons-compress necesario para POI pero a versión ligera
    implementation(libs.commons.compress)

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

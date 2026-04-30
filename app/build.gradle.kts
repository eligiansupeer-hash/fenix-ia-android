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
            // PdfBox Android
            excludes += "META-INF/DEPENDENCIES"
        }
    }
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

    // Serialización Kotlin
    implementation(libs.kotlinx.serialization.json)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // JavaScriptSandbox
    implementation(libs.javascriptengine)

    // TensorFlow Lite
    implementation(libs.tensorflow.lite)

    // ObjectBox runtime
    implementation(libs.objectbox.android)

    // ── Extracción de texto de documentos ─────────────────────────────────────

    // Apache POI — extracción de texto DOCX/DOC (R-04: excluye xmlbeans pesado)
    implementation(libs.apache.poi.ooxml) {
        exclude(group = "org.apache.xmlbeans")
        exclude(group = "com.github.virtuald")
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "org.apache.commons", module = "commons-compress")
    }
    implementation(libs.commons.compress)

    // PdfBox Android — extrae texto NATIVO del stream PDF sin renderizar bitmaps.
    // Funciona en PDFs digitales (generados con Word, LibreOffice, etc).
    // R-04 compliant: procesa el stream directamente, no carga el PDF en heap de golpe.
    implementation(libs.pdfbox.android)

    // ML Kit Text Recognition — OCR para PDFs escaneados (imágenes sin texto embebido).
    // El modelo (~3 MB) se descarga via Play Services en el primer uso.
    // Fallback: si no hay Play Services, usa el modelo bundled en el APK.
    implementation(libs.mlkit.text.recognition)

    // ── Tests ──────────────────────────────────────────────────────────────────
    testImplementation(libs.junit5)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

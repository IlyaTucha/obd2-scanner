import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Загрузка ключей из local.properties (не коммитится).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val yandexApiKey: String =
    localProps.getProperty("YANDEX_API_KEY")
        ?: (project.findProperty("YANDEX_API_KEY") as? String)
        ?: ""

// Версия из version.properties (коммитится). VERSION_CODE авто-инкрементится при release-сборке.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) FileInputStream(versionPropsFile).use { load(it) }
}
val appVersionCode = (versionProps.getProperty("VERSION_CODE") ?: "1").trim().toInt()
val appVersionName = (versionProps.getProperty("VERSION_NAME") ?: "1.0").trim()

// Данные постоянной release-подписи из keystore.properties (НЕ коммитится).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists()

android {
    namespace = "com.obd2monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.obd2monitor"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "YANDEX_API_KEY", "\"$yandexApiKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)

    implementation(libs.play.services.location)

    // Yandex MapKit — нативный мобильный SDK (рисуем карту только в Trip Summary).
    implementation(libs.yandex.mapkit)

    implementation(libs.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.splashscreen)
    implementation(libs.accompanist.permissions)
}

kapt { correctErrorTypes = true }

// Авто-инкремент VERSION_CODE при каждой release-сборке (versionCode должен строго расти).
tasks.register("bumpVersionCode") {
    doLast {
        val props = Properties().apply {
            if (versionPropsFile.exists()) FileInputStream(versionPropsFile).use { load(it) }
        }
        val current = (props.getProperty("VERSION_CODE") ?: "0").trim().toInt()
        val next = current + 1
        val lines = versionPropsFile.readLines().map { line ->
            if (line.trimStart().startsWith("VERSION_CODE=")) "VERSION_CODE=$next" else line
        }
        versionPropsFile.writeText(lines.joinToString(System.lineSeparator()) + System.lineSeparator())
        println("VERSION_CODE: $current -> $next")
    }
}

tasks.matching { it.name.startsWith("package") && it.name.endsWith("Release") }.configureEach {
    dependsOn("bumpVersionCode")
}

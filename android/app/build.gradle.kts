import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Firebase: with app/google-services.json (from the Firebase console, not committed) the app talks to the
// real project. Without it, or with firebase.emulator=true in local.properties, a debug build talks to the local
// emulator (core/firebase, `npm run emulators`; USB phone: `adb reverse tcp:43099 tcp:43099`).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
if (file("google-services.json").exists())
    pluginManager.apply("com.google.gms.google-services")

android {
    namespace = "com.chaosnoteviewer"
    compileSdk = 37      // the current Compose libraries need 37; does not limit which phones can install

    defaultConfig {
        // permanent: Firebase registers the app under this id
        applicationId = "com.chaosnoteviewer"
        minSdk = 28        // Android 9: the platform has ChaCha20-Poly1305 from here on (crypto/NoteCrypto.kt)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "FIREBASE_EMULATOR", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            val emulator = localProps.getProperty("firebase.emulator") == "true" || !file("google-services.json").exists()
            buildConfigField("boolean", "FIREBASE_EMULATOR", emulator.toString())
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // the instrumented tests build a database from the canonical SQL migrations to check the Room schema
    sourceSets["androidTest"].assets.directories.add("../../core/storage/migrations")
    // the shared encryption test vectors (core/crypto/vectors.json) are checked on the PC and on the phone
    sourceSets["androidTest"].assets.directories.add("../../core/crypto")
    sourceSets["test"].kotlin.directories.add("src/sharedTest/java")
    sourceSets["androidTest"].kotlin.directories.add("src/sharedTest/java")

    // the app's own icons are shared with the desktop app: core/assets/icons/*.png are packed as assets (icons/<name>.png)
    sourceSets["main"].assets.directories.add("../../core/assets")
    androidResources.ignoreAssetsPattern = "chaosnoteviewer.svg"

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Room writes each schema version here (committed): the base for future migrations
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.reorderable)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

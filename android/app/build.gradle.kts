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
// Release signing: keystore.properties (not committed, see keystore.properties.example) or, in CI, environment variables.
// Without either the release build is simply left unsigned.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(property: String, env: String): String? = keystoreProps.getProperty(property) ?: System.getenv(env)

// Version: -PversionName=1.2.3 (the release workflow passes the tag's version). The code is major*10000 + minor*100 + patch,
// so every later version is higher, as Android requires for updates.
val appVersionName = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = (findProperty("versionCode") as String?)?.toInt()
    ?: appVersionName.split(".").map { it.toInt() }.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

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
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("boolean", "FIREBASE_EMULATOR", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            signingValue("storeFile", "ANDROID_KEYSTORE_FILE")?.let { storeFile = rootProject.file(it) }
            // one password for the keystore and the key (keytool's default PKCS12 format)
            storePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
            keyPassword = storePassword
            keyAlias = "chaosnoteviewer"
        }
    }

    buildTypes {
        debug {
            val emulator = localProps.getProperty("firebase.emulator") == "true" || !file("google-services.json").exists()
            buildConfigField("boolean", "FIREBASE_EMULATOR", emulator.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // signed when a keystore is configured, otherwise left unsigned (app-release-unsigned.apk)
            if (signingConfigs.getByName("release").storeFile != null) signingConfig = signingConfigs.getByName("release")
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
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.reorderable)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.code.scanner)     // scanning the key's QR code, no camera permission
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

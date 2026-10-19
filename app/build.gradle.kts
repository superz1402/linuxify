import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Stable signing identity: the SAME key signs debug and release builds, so an
// update can always install over a previous build without uninstalling first.
// keystore/keystore.properties + linuxify-release.jks are committed on purpose:
// losing this key would force every user to uninstall on the next update.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.linuxify.app"
    compileSdk = 35

    signingConfigs {
        create("linuxify") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.linuxify.app"
        minSdk = 26
        // targetSdk MUST stay below 29: Android 10+ forbids executing binaries
        // from app-private storage for apps targeting >= 29 (SELinux
        // untrusted_app domain), and the whole Linux userspace (proot guests)
        // lives in app-private storage. targetSdk 28 keeps the app in the
        // untrusted_app_27 domain, which still allows exec — this is the same
        // approach Termux, UserLAnd and every proot-based Android app uses.
        // compileSdk stays 35 so we keep modern toolchain/API availability.
        targetSdk = 28
        versionCode = 14
        versionName = "0.1.0-alpha.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = if (keystoreProperties.isNotEmpty()) {
                signingConfigs.getByName("linuxify")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            // Minification disabled for alpha: R8 exhausts memory on 4 GB CI hosts and
            // the keep-rules already retain the entire terminal stack. Revisit for
            // production releases with a dedicated signing identity (docs/BUILDING.md).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProperties.isNotEmpty()) {
                signingConfigs.getByName("linuxify")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
    lint {
        // targetSdk 28 is a deliberate, documented decision (see defaultConfig
        // above and docs/RUNTIME.md): proot guests exec from app-private
        // storage, which Android blocks for targetSdk >= 29. This app is
        // sideloaded, not distributed via Play, so the Play Store policy lint
        // does not apply.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Real org.json for JVM unit tests (the android.jar stub throws "not mocked")
    testImplementation("org.json:json:20240303")
}

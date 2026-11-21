import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Load signing credentials from local.properties (never committed to git).
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "au.com.michaelcarey.artouchpad"
    compileSdk = 35
    testBuildType = providers.gradleProperty("deviceTestBuildType").getOrElse("debug")

    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("KEYSTORE_PATH", "release.jks"))
            storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = localProps.getProperty("KEY_ALIAS", "artouchpad")
            keyPassword = localProps.getProperty("KEY_PASSWORD", "")
        }
    }

    defaultConfig {
        applicationId = "au.com.michaelcarey.artouchpad"
        // minSdk 34 (Android 14): required for WindowManager.currentWindowMetrics and
        // the DisplayManager APIs used to detect the glasses as a secondary display.
        minSdk = 34
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.3"
        manifestPlaceholders["appLabel"] = "AR Touchpad"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Phone builds use ARM64. Debug also runs in an x86_64 emulator.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            ndk.abiFilters += "x86_64"
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "AR Touchpad Debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("prod") {
            initWith(getByName("release"))
            manifestPlaceholders["appLabel"] = "AR Touchpad Prod"
            // Local stable installs use the local key unless a release key is configured.
            // Production and Debug have separate application IDs and app data.
            signingConfig = if (localProps.getProperty("KEYSTORE_PATH") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
        }
        // Release behavior with a local debug signature, for repeatable device profiling.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            manifestPlaceholders["appLabel"] = "AR Touchpad Benchmark"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
        compose = true
        // aidl: generates IMouseService Java stubs from IMouseService.aidl, used for
        // IPC between the app process and the Shizuku UserService (MouseService).
        aidl = true
    }
    sourceSets.getByName("benchmark") {
        java.srcDir("src/debug/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }

    lint {
        // BlockedPrivateApi: we deliberately access InputManagerGlobal.injectInputEvent and
        // InputEvent.setDisplayId via reflection, bypassed at runtime by HiddenApiBypass.
        disable += "BlockedPrivateApi"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material)
    // Shizuku: lets the app bind MouseService as a shell-uid UserService so it can
    // access /dev/uinput and inject input events without root.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // HiddenApiBypass: allows reflection access to InputManagerGlobal.injectInputEvent
    // and InputEvent.setDisplayId, which are restricted hidden APIs on Android 9+.
    implementation(libs.hiddenapibypass)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    "benchmarkImplementation"(libs.androidx.ui.test.manifest)
}

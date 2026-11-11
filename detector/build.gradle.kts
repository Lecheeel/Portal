plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.system.location.service.detector"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    // The Windows sandbox cannot write ~/.android; use a workspace-local debug key when present.
    // CI/Linux falls back to the default debug keystore.
    val localDebugKeystore = rootProject.file(".local-keystore/detector-debug.keystore")
    if (localDebugKeystore.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = localDebugKeystore
            }
        }
    }

    defaultConfig {
        applicationId = "com.system.location.service.detector"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// No :app/:xposed/:core dependency and no third-party runtime dependency: ADR-020 requires a
// detector that cannot share code paths with the implementation it evaluates.
dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
}

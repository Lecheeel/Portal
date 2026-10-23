plugins { alias(libs.plugins.android.application) }

val integrityVersion = "0.1.0"
val integrityCode = 1
val signingPath = providers.environmentVariable("INTEGRITY_KEYSTORE_PATH").orNull
android {
    namespace = "io.github.lecheeel.integrity"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.lecheeel.locationintegrity"
        minSdk = 26
        targetSdk = 37
        versionCode = integrityCode
        versionName = integrityVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REVISION", "\"" + providers.gradleProperty("BUILD_REVISION").orElse("local").get().replace(Regex("[^A-Za-z0-9._-]"), "").take(64) + "\"")
    }
    if (!signingPath.isNullOrBlank()) signingConfigs {
        create("integrityRelease") {
            storeFile = file(signingPath)
            storePassword = providers.environmentVariable("INTEGRITY_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("INTEGRITY_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("INTEGRITY_KEY_PASSWORD").get()
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!signingPath.isNullOrBlank()) signingConfig = signingConfigs.getByName("integrityRelease")
        }
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
dependencies {
    implementation(project(":integrity-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.fragment:fragment:1.6.2")
    implementation("androidx.activity:activity:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

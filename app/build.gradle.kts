import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

val releaseVersionName = providers.gradleProperty("APP_VERSION_NAME").orElse("1.2.0")
val releaseVersionCode = providers.gradleProperty("APP_VERSION_CODE").orElse("1790000001").map(String::toInt)
val revision = providers.gradleProperty("BUILD_REVISION").orElse("unknown")
require(releaseVersionName.get().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "APP_VERSION_NAME must be major.minor.patch" }
require(releaseVersionCode.get() in 1..2_100_000_000) { "Invalid APP_VERSION_CODE" }
require(revision.get().matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid BUILD_REVISION" }

android {
    namespace = "com.system.location.service"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.system.location.service"
        minSdk = 31
        targetSdk = 37
        versionCode = releaseVersionCode.get()
        versionName = releaseVersionName.get()
        buildConfigField("String", "GIT_REVISION", "\"${revision.get()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

//        val googleServicesFile = project.file("google-services.json")
//        if (!googleServicesFile.exists()) {
//            throw GradleException("在 CI 环境中必须提供 google-services.json 文件!")
//        }

        manifestPlaceholders["BUGLY_APPID"] = "222f9ef298"
        manifestPlaceholders["AMAP_ANDROID_KEY"] =
            System.getenv("AMAP_ANDROID_KEY") ?: ""

        manifestPlaceholders["APP_CHANNEL"] = "local"

    }

    buildTypes {
        release {
            manifestPlaceholders["APP_CHANNEL"] = "release"
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["APP_VERSION"] = defaultConfig.versionName ?: "UnknownVersion"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "false"
        }

        debug {
            manifestPlaceholders["APP_CHANNEL"] = "debug"
            manifestPlaceholders["APP_VERSION"] = "${defaultConfig.versionName}-debug"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "true"
        }
    }


    flavorDimensions.add("mode")

    productFlavors {
        create("app") {
            dimension = "mode"
            ndk {
                println("Full architecture and full compilation.")
                abiFilters.add("arm64-v8a")
                abiFilters.add("x86_64")
            }
        }
        create("arm64") {
            dimension = "mode"
            ndk {
                println("Full compilation of arm64 architecture")
                abiFilters.add("arm64-v8a")
            }
        }
        create("x64") {
            dimension = "mode"
            ndk {
                println("Full compilation of x64 architecture")
                abiFilters.add("x86_64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/armeabi/**"
            excludes += "lib/x86/**"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/DEPENDENCIES.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/dependencies.txt"
            excludes += "/META-INF/LGPL2.1"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/services/reactor.blockhound.integration.BlockHoundIntegration"
            excludes += "lib/armeabi/**"
            excludes += "lib/x86/**"
        }
    }
    sourceSets {
    }
    configureAppSigningConfigsForRelease(project)
}

fun configureAppSigningConfigsForRelease(project: Project) {
    val keystorePath: String? = System.getenv("KEYSTORE_PATH")
    if (keystorePath.isNullOrBlank()) {
        return
    }
    project.configure<ApplicationExtension> {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("KEYSTORE_PATH"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV2Signing = true
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.findByName("release")
            }
            debug {
                signingConfig = signingConfigs.findByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":xposed"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.bugly)

    implementation(libs.amap.map3d.location.search)
    implementation(libs.geotools)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Export stable distribution names using the public artifacts API; packaged APKs stay untouched.
val exportReleaseApks = tasks.register("exportReleaseApks")
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val abi = when (variant.flavorName) { "app" -> "all"; "x64" -> "x86_64"; else -> "arm64" }
        val export = tasks.register<Copy>("export${variant.name.replaceFirstChar { it.uppercase() }}Apk") {
            from(variant.artifacts.get(SingleArtifact.APK))
            include("*.apk")
            into(layout.buildDirectory.dir("outputs/distribution"))
            rename { "LocationService-v${releaseVersionName.get()}-$abi.apk" }
        }
        exportReleaseApks.configure { dependsOn(export) }
    }
}

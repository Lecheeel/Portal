import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.system.location.service"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.system.location.service"
        minSdk = 26
        targetSdk = 36
        versionCode = getVersionCode()
        versionName = "1.0.4" + ".r${getGitCommitCount()}." + getVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a"))
        }

//        val googleServicesFile = project.file("google-services.json")
//        if (!googleServicesFile.exists()) {
//            throw GradleException("在 CI 环境中必须提供 google-services.json 文件!")
//        }

        manifestPlaceholders["BUGLY_APPID"] = "222f9ef298"
        manifestPlaceholders["AMAP_ANDROID_KEY"] =
            System.getenv("AMAP_ANDROID_KEY") ?: ""

        val publicIp = try {
            val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
            val process = if (isWindows) {
                Runtime.getRuntime().exec(arrayOf(
                    "powershell.exe",
                    "-command",
                    "(Invoke-WebRequest -Uri 'https://api.ipify.org' -UseBasicParsing).Content," +
                            "(Invoke-WebRequest -Uri 'https://ifconfig.me' -UseBasicParsing).Content," +
                            "(Invoke-WebRequest -Uri 'https://icanhazip.com' -UseBasicParsing).Content," +
                            "(Invoke-WebRequest -Uri 'https://checkip.amazonaws.com' -UseBasicParsing).Content" +
                            " | Select-Object -First 1"
                ))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c",
                    "curl -s https://api.ipify.org || " +
                            "curl -s https://ifconfig.me || " +
                            "curl -s https://icanhazip.com || " +
                            "curl -s https://checkip.amazonaws.com"
                ))
            }
            val reader = process.inputStream.bufferedReader()
            val ip = reader.readLine()?.trim() ?: "unknown"
            if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+".toRegex())) ip else "unknown"
        } catch (e: Exception) {
            println("Error getting public IP address: ${e.message}")
            "unknown"
        }

        val deviceName = try {
            val process = Runtime.getRuntime().exec("hostname")
            val reader = process.inputStream.bufferedReader()
            reader.readLine()?.trim() ?: "unknown"
        } catch (e: Exception) {
            println("Error getting device name: ${e.message}")
            "unknown"
        }
        val buildPath = project.rootDir.absolutePath.replace("\\", "/")
        manifestPlaceholders["BUGLY_BUILD_ENV"] = "IP:$publicIp,DEVICE:$deviceName,PATH:$buildPath"
        manifestPlaceholders["APP_CHANNEL"] = "$publicIp-$deviceName"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["APP_VERSION"] = defaultConfig.versionName ?: "UnknownVersion"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "false"
        }

        debug {
            manifestPlaceholders["APP_VERSION"] = "${defaultConfig.versionName}-debug"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "true"
        }
    }

    // APK renaming moved to androidComponents.onVariants at bottom of file

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
    implementation(project(":xposed"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.fastjson)
    implementation(libs.kotlin.reflect)

    implementation(libs.bugly)

    implementation(libs.amap.map3d.location.search)
    implementation(libs.geotools)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

fun getGitCommitCount(): Int {
    val result = providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }
    return result.standardOutput.asText.get().trim().toInt()
}

fun getGitCommitHash(): String {
    val result = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }
    return result.standardOutput.asText.get().trim()
}

fun getVersionCode(): Int {
    return (System.currentTimeMillis() / 1000L).toInt()
}

fun getVersionName(): String {
    return getGitCommitHash()
}

// AGP 9: rename APK outputs via the new variant API (cast to internal impl for outputFileName)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val impl = output as VariantOutputImpl
            val abiName = when (val abi = impl.outputFileName.get().split("-")[1].split(".apk")[0]) {
                "app" -> "all"
                "x64" -> "x86_64"
                else -> abi
            }
            impl.outputFileName.set("LocationService-v" + output.versionName.get() + "-" + abiName + ".apk")
        }
    }
}


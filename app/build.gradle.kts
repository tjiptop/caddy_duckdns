plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.caddy.proxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.caddy.proxy"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        multiDexEnabled = true

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.register("copyApksToSpecialFolder") {
    group = "build"
    description = "Copies generated APKs to the dedicated apks/ folder"
    doLast {
        val destFolder = rootProject.file("apks")
        if (!destFolder.exists()) {
            destFolder.mkdirs()
        }
        val buildOutputs = file("build/outputs/apk")
        if (buildOutputs.exists()) {
            buildOutputs.walkTopDown().filter { it.extension == "apk" }.forEach { apk ->
                val targetName = when {
                    apk.name.contains("arm64-v8a") -> "CaddyProxy-Android-arm64-v8a.apk"
                    apk.name.contains("armeabi-v7a") -> "CaddyProxy-Android-armeabi-v7a.apk"
                    apk.name.contains("universal") -> "CaddyProxy-Android-Universal.apk"
                    else -> apk.name
                }
                apk.copyTo(File(destFolder, targetName), overwrite = true)
                println("Output APK disalin ke folder khusus: apks/$targetName (${apk.length() / 1024 / 1024} MB)")
            }
        }
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    finalizedBy("copyApksToSpecialFolder")
}

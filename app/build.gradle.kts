plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.boss.nexu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.boss.jarvis"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
    }

    signingConfigs {
        create("jarvisRelease") {
            val ksPath = System.getenv("JARVIS_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD") ?: "jarvis2026"
                keyAlias = System.getenv("JARVIS_KEY_ALIAS") ?: "jarvis"
                keyPassword = System.getenv("JARVIS_KEY_PASSWORD") ?: "jarvis2026"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val relSigning = signingConfigs.findByName("jarvisRelease")
            signingConfig = if (relSigning?.storeFile != null) {
                relSigning
            } else {
                signingConfigs.getByName("debug")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}

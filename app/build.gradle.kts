plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The signing key is supplied by the build (GitHub secret), never stored in the repo.
val releaseKeystore = file("release.keystore")

android {
    namespace = "com.amclub.wabridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amclub.birbal"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("birbal") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "birbal"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (releaseKeystore.exists()) signingConfig = signingConfigs.getByName("birbal")
        }
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

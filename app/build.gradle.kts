plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pixelgd.glassgate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pixelgd.glassgate"
        minSdk = 34
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2.0"
    }

    // Standard installs without Play Protect's sideloading block. Plus adds the features that
    // need notification access (badges) and contacts (Spotlight people search); Play Protect
    // blocks those at install from a browser or file manager, so they live in their own APK.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            isDefault = true
            buildConfigField("boolean", "FEATURE_BADGES", "false")
            buildConfigField("boolean", "FEATURE_CONTACTS", "false")
        }
        create("plus") {
            dimension = "edition"
            buildConfigField("boolean", "FEATURE_BADGES", "true")
            buildConfigField("boolean", "FEATURE_CONTACTS", "true")
        }
    }

    signingConfigs {
        // Shared debug key, committed on purpose, so every debug build installs over the last one.
        getByName("debug") {
            storeFile = rootProject.file("tools/debug.jks")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
        // The owner's private key, decoded by CI from the GLASSGATE_KEYSTORE_B64 secret.
        val releaseStore = System.getenv("GLASSGATE_KEYSTORE")?.let { file(it) }
        if (releaseStore != null && releaseStore.exists()) {
            create("release") {
                storeFile = releaseStore
                storePassword = System.getenv("GLASSGATE_KEYSTORE_PASSWORD")
                keyAlias = "glassgate"
                keyPassword = System.getenv("GLASSGATE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the secret (forks, PRs from forks) fall back to the public debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
}

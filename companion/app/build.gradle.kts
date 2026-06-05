plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.f4nu.ishiki"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.f4nu.ishiki"
        minSdk = 26
        targetSdk = 34
        versionCode = 201
        versionName = "0.2.1"
    }

    signingConfigs {
        create("release") {
            // Configured only when a keystore is provided via env (CI secrets).
            // Without it, a release build is simply unsigned, so local and F-Droid
            // builds still work.
            System.getenv("KEYSTORE_FILE")?.let { ks ->
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

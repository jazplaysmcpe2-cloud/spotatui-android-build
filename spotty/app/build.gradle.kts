plugins {
    id("com.android.application")
}

// Match release tag/run number so Spotty can identify newer releases accurately.
val spottyCiBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

android {
    namespace = "com.spotty.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spotty.music"
        minSdk = 26
        targetSdk = 35
        versionCode = spottyCiBuildNumber
        versionName = "0.1.0+$spottyCiBuildNumber"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "eu.kanade.tachiyomi.animeextension.en.toonstream"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.animeextension.en.toonstream"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":lib:gogostreamextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:streamlareextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:streamwishextractor"))
}

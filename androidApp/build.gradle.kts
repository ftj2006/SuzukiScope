plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "com.suzukiscan.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.suzukiscan.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "pj-viewer-${versionName}-${name}.apk"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}

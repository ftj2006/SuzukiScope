plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("com.github.triplet.play")
}

play {
    val credentialsPath = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON")
        .orNull
        ?: "/home/paul/.keys/pj-sz-vuewer-cb144e53c539.json"
    serviceAccountCredentials.set(file(credentialsPath))
    track.set("internal")
    defaultToAppBundles.set(true)
}

android {
    namespace = "com.suzukiscope.android"
    compileSdk = 36

    // Committed so local and CI builds always sign with the same key — otherwise every CI run
    // (which has no persisted ~/.android/debug.keystore) generates a new one, and installing a
    // newer build over an older one fails with "conflicts with an existing package". Separate
    // release.keystore for the release build type — for Play Console Internal App Sharing this
    // upload key doesn't need to be ultra-secret, but keeping it distinct from the debug key
    // means debug builds never accidentally satisfy a release-signature check.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "pjszviewer"
            keyAlias = "pjszviewerrelease"
            keyPassword = "pjszviewer"
        }
    }

    defaultConfig {
        applicationId = "za.co.pj.szviewer"
        minSdk = 26
        targetSdk = 36
        versionCode = 39
        versionName = "1.5.29"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
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
            output.outputFileName = "SuzukiScope-${versionName}-${name}.apk"
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

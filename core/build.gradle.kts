plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()
    // Android target is added by the app modules consuming this as a JVM dependency for now;
    // switch to a real Android target once Android Studio/SDK is available to verify the build.
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
        // Used by androidApp (runs on ART with java.net.Socket support):
        // plain TCP transport for ELM327 Wi-Fi adapters (192.168.0.10:35000), per APK's HostPort/WiFiDevice.
        val jvmMain by getting {
            dependencies {}
        }
    }
}

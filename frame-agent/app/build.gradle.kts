import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release builds are signed with your own key if you have one, so an update installs over
// the copy already on the frame. Point FRAME_AGENT_SIGNING_DIR at a folder holding
// signing.keystore and signing.properties (storePassword, keyAlias, keyPassword), or put them
// in frame-agent/signing/. Without them, release builds use the debug key.
val signingDir = System.getenv("FRAME_AGENT_SIGNING_DIR")?.let(::file) ?: rootProject.file("signing")
val signingProps = Properties().apply {
    signingDir.resolve("signing.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val hasSigningKey = signingDir.resolve("signing.keystore").exists() && !signingProps.isEmpty

android {
    namespace = "net.blanchard.frameagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.blanchard.frameagent"
        minSdk = 29
        // Frames run Android 10. Targeting 29 avoids the API 34 foreground-service-type rules.
        targetSdk = 29
        versionCode = 21
        versionName = "0.5.6"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("frames") {
                storeFile = signingDir.resolve("signing.keystore")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasSigningKey) "frames" else "debug")
        }
    }

    lint {
        // Never published to Play; the frames are Android 10.
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
        buildConfig = true
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
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // 1.4 is the newest line that builds with compileSdk 34.
    implementation("androidx.media3:media3-exoplayer:1.4.1")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is stubbed out in local unit tests.
    testImplementation("org.json:json:20240303")
}

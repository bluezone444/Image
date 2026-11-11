plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.chargelog"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.chargelog"
        minSdk = 26
        // 34: keeps classic (non edge-to-edge) window layout while supporting specialUse FGS.
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        // Fixed key committed to the repo so every CI build can be installed over the
        // previous one without losing recorded data. Personal sideload use only.
        create("shared") {
            storeFile = rootProject.file("signing/chargelog.jks")
            storePassword = "chargelog"
            keyAlias = "chargelog"
            keyPassword = "chargelog"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

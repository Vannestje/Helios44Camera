plugins {
    id("com.android.application")
}

android {
    namespace = "com.someday.helios44"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.someday.helios44"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = file("helios-debug.keystore")
            storePassword = "helios44"
            keyAlias = "helios44"
            keyPassword = "helios44"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

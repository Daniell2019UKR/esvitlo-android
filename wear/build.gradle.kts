plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ua.pp.yesvitlo.wear"
    compileSdk = 36
    defaultConfig {
        applicationId = "ua.pp.yesvitlo"
        minSdk = 30
        targetSdk = 36
        versionCode = 100019
        versionName = "1.9.0"
    }
    buildTypes {
        debug { applicationIdSuffix = ".wear.debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":schedule"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.wear.compose:compose-material3:1.5.3")
}

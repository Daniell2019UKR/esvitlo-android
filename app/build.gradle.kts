plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services") version "4.4.4" apply false
}
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "ua.pp.yesvitlo"
    compileSdk = 36
    defaultConfig {
        applicationId = "ua.pp.yesvitlo"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.9.1"
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "GITHUB_UPDATES", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "GITHUB_UPDATES", "false")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":schedule"))
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("Google Play flags the old Fragment pulled in by transitive dependencies")
        }
    }
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha15")
}

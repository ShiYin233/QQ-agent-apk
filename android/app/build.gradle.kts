plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.qqagent.android.gate"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "cn.qqagent.android.gate"
        minSdk = 31
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-gate-check"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file(".local-signing/debug.keystore")
    }
    lint {
        // User-approved sideload-only targetSdk 28; keep the Play policy diagnostic visible.
        warning += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.verba.interpretation"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.verba.interpretation"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AGENT_HTTP_URL", "\"https://api.example.com\"")
        buildConfigField("String", "CLOUD_API_URL", "\"https://cloud-api.example.com\"")
        buildConfigField("String", "TRANSLATION_WS_URL", "\"wss://api.example.com/v1/translation\"")
        buildConfigField("String", "TRANSLATION_ORIGIN", "\"\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "AGENT_HTTP_URL", "\"http://114.132.83.144:18765\"")
            // Temporary development endpoint. Release uses the HTTPS-only production placeholder below.
            buildConfigField("String", "CLOUD_API_URL", "\"http://114.132.83.144:8080\"")
            buildConfigField("String", "TRANSLATION_WS_URL", "\"ws://114.132.83.144:18765/ws/translate\"")
            buildConfigField("String", "TRANSLATION_ORIGIN", "\"http://114.132.83.144:15173\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "AGENT_HTTP_URL", "\"https://api.example.com\"")
            buildConfigField("String", "CLOUD_API_URL", "\"https://cloud-api.example.com\"")
            buildConfigField("String", "TRANSLATION_WS_URL", "\"wss://api.example.com/v1/translation\"")
            buildConfigField("String", "TRANSLATION_ORIGIN", "\"\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20250517")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

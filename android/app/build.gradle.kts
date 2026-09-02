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
        buildConfigField("String", "AGENT_HTTP_URL", "\"https://47-129-170-16.sslip.io\"")
        buildConfigField("String", "CLOUD_API_URL", "\"https://47-129-170-16.sslip.io\"")
        buildConfigField("String", "TRANSLATION_WS_URL", "\"wss://47-129-170-16.sslip.io/ws/translate\"")
        buildConfigField("String", "TRANSLATION_ORIGIN", "\"https://47-129-170-16.sslip.io\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "AGENT_HTTP_URL", "\"https://47-129-170-16.sslip.io\"")
            // Cloud API uses the EC2 HTTPS edge in both debug and release builds.
            buildConfigField("String", "CLOUD_API_URL", "\"https://47-129-170-16.sslip.io\"")
            buildConfigField("String", "TRANSLATION_WS_URL", "\"wss://47-129-170-16.sslip.io/ws/translate\"")
            buildConfigField("String", "TRANSLATION_ORIGIN", "\"https://47-129-170-16.sslip.io\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "AGENT_HTTP_URL", "\"https://47-129-170-16.sslip.io\"")
            buildConfigField("String", "CLOUD_API_URL", "\"https://47-129-170-16.sslip.io\"")
            buildConfigField("String", "TRANSLATION_WS_URL", "\"wss://47-129-170-16.sslip.io/ws/translate\"")
            buildConfigField("String", "TRANSLATION_ORIGIN", "\"https://47-129-170-16.sslip.io\"")
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
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

val releaseSigningProperties = Properties().also { properties ->
    val path = System.getenv("YANSHU_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.local/share/verba-release-signing/signing.properties"
    file(path).takeIf { it.isFile }?.inputStream()?.use(properties::load)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.verba.interpretation"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.verba.interpretation"
        minSdk = 26
        targetSdk = 36
        // versionName is the human-facing China Standard Time timestamp: YYYYMMDD.HHmm.
        // Android versionCode remains a compact monotonic integer (max 2,100,000,000).
        versionCode = 9
        versionName = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmm"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AGENT_HTTP_URL", "\"https://47-129-170-16.sslip.io\"")
        buildConfigField("String", "CLOUD_API_URL", "\"https://47-129-170-16.sslip.io\"")
        buildConfigField("String", "TRANSLATION_WS_URL", "\"wss://47-129-170-16.sslip.io/ws/translate\"")
        buildConfigField("String", "TRANSLATION_ORIGIN", "\"https://47-129-170-16.sslip.io\"")
    }

    signingConfigs {
        if (releaseSigningProperties.isNotEmpty()) {
            create("production") {
                storeFile = file(requireNotNull(releaseSigningProperties.getProperty("storeFile")))
                storePassword = requireNotNull(releaseSigningProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigningProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigningProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "AGENT_HTTP_URL", "\"https://47-129-170-16.sslip.io\"")
            // Cloud API uses the EC2 HTTPS edge in both debug and release builds.
            buildConfigField("String", "CLOUD_API_URL", "\"https://47-129-170-16.sslip.io\"")
            buildConfigField("String", "TRANSLATION_WS_URL", "\"wss://47-129-170-16.sslip.io/ws/translate\"")
            buildConfigField("String", "TRANSLATION_ORIGIN", "\"https://47-129-170-16.sslip.io\"")
        }
        release {
            signingConfigs.findByName("production")?.let { signingConfig = it }
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
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

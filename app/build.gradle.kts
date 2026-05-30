plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun configValue(name: String): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .getOrElse("")

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.kafkasl.phonewhisper"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.kafkasl.phonewhisper"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.3.0"

        ndk { abiFilters += "arm64-v8a" }

        val backendUrl = configValue("PHONE_WHISPER_BACKEND_URL").trim()
        val backendToken = configValue("PHONE_WHISPER_BACKEND_TOKEN").trim()
        val backendUploadRequested = configValue("PHONE_WHISPER_BACKEND_UPLOAD_ENABLED")
            .ifBlank { "true" }
            .toBooleanStrictOrNull() ?: false
        val backendUploadEnabled = backendUploadRequested && backendUrl.isNotBlank() && backendToken.isNotBlank()

        buildConfigField("String", "BACKEND_BASE_URL", backendUrl.asBuildConfigString())
        buildConfigField("String", "BACKEND_API_TOKEN", backendToken.asBuildConfigString())
        buildConfigField("boolean", "BACKEND_UPLOAD_ENABLED", backendUploadEnabled.toString())
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

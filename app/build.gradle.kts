import java.util.Properties

// Build-time OpenRouter key: env OPENROUTER_API_KEY, else local.properties `openrouter.api.key`.
// NEVER committed (local.properties + *.apk are gitignored). Empty string if absent.
fun resolveEmbeddedKey(): String {
    System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        val p = Properties(); lp.inputStream().use { p.load(it) }
        p.getProperty("openrouter.api.key")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("app.cash.paparazzi")
}
android {
    namespace = "com.autodrops.agent"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.autodrops.agent"; minSdk = 26; targetSdk = 34; versionCode = 4; versionName = "3.1"
        buildConfigField("String", "EMBEDDED_API_KEY", "\"${resolveEmbeddedKey()}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.mobile:dadb:1.2.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

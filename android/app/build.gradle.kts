plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// For an in-place update use the SAME keystore that signed the installed APK.
// The keystore path and passwords are supplied by protected CI secrets, never committed.
val compatibleKeystore = System.getenv("AI_REMOTE_SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.example.airemote"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.example.airemote"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 75
        versionName = "2.14.41-in-app-oauth-hardening"
    }
    signingConfigs {
        if (!compatibleKeystore.isNullOrBlank()) {
            create("existingInstallation") {
                storeFile = file(compatibleKeystore)
                storePassword = System.getenv("AI_REMOTE_SIGNING_STORE_PASSWORD")
                    ?: throw GradleException("CI signing store password missing")
                keyAlias = System.getenv("AI_REMOTE_SIGNING_KEY_ALIAS")
                    ?: throw GradleException("CI signing key alias missing")
                keyPassword = System.getenv("AI_REMOTE_SIGNING_KEY_PASSWORD")
                    ?: throw GradleException("CI signing key password missing")
            }
        }
    }
    buildTypes {
        debug {
            // Intentionally preserve old debug applicationId. CI verifies its
            // exact match against the owner's PREVIOUS installed signed APK.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-test"
            if (!compatibleKeystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("existingInstallation")
            } else {
                // Compile-only output: never silently replace the installation key.
                signingConfig = null
            }
        }
        release { isMinifyEnabled = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Device-side SQLiteOpenHelper and ContentValues exercised against Robolectric SQLite.
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Browser-owned sign-in within the app; never collect ChatGPT passwords or 2FA codes.
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    // Offline OCR model; no Windows or Python service.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // T043 Stage 3: RFC 9180 HPKE + Ed25519; explicit FTPS only.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("commons-net:commons-net:3.13.0")
}

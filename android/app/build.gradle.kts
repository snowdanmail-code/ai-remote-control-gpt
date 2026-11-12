plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// For an in-place update use the SAME keystore that signed the installed APK.
// The keystore path and passwords are supplied by protected CI secrets, never committed.
val compatibleKeystore = System.getenv("AI_REMOTE_SIGNING_KEYSTORE_PATH")

// Build-time ONLY: bundle pinned Russian+English OCR language models.
// APK needs no account, cloud OCR service, model installer or runtime download.
// Explicit SHA-512s from the public tessdata_fast-4.1.0 distribution manifest.
val lobbyOcrAssets = layout.buildDirectory.dir("generated/lobby-ocr-assets")
val prepareLobbyOcrModels = tasks.register("prepareLobbyOcrModels") {
    outputs.dir(lobbyOcrAssets)
    doLast {
        val dest = lobbyOcrAssets.get().asFile.resolve("tessdata")
        check(dest.isDirectory || dest.mkdirs()) { "Cannot create OCR assets" }
        val hashes = mapOf(
            "rus" to "65254c36df8274cee0a2c32e9632f3c2c0032314bf0a99a4c23309e862da7c038ed85745d58697a814f44c656f22ef5a7815ae2bec33f80de1de8e1ea9dd",
            "eng" to "90aadb5e0c368781f94add808f6b4dd31d8e244c9fecbe6a233070cac9a4fc071fd23a09e4a7e041db8e26880a6debbdf32a6b701c28687b2ed1cac81f4c1d14"
        )
        val sizes = mapOf("rus" to 3_861_738, "eng" to 4_113_088)
        fun sha512(data: ByteArray) = java.security.MessageDigest.getInstance("SHA-512")
            .digest(data).joinToString("") { "%02x".format(it) }
        for ((lang, expectedSha) in hashes) {
            val target = dest.resolve("$lang.traineddata")
            if (target.exists() && target.length() == sizes.getValue(lang).toLong() &&
                sha512(target.readBytes()) == expectedSha) continue
            val url = java.net.URI(
                "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/$lang.traineddata"
            ).toURL()
            val conn = url.openConnection().apply {
                connectTimeout = 18_000
                readTimeout = 35_000
            }
            val data = conn.getInputStream().use { it.readBytes() }
            check(data.size == sizes.getValue(lang) && sha512(data) == expectedSha) {
                "Untrusted OCR model download: $lang (SHA-512/size mismatch)"
            }
            val tmp = dest.resolve("$lang.traineddata.part")
            tmp.writeBytes(data)
            check(tmp.renameTo(target)) { "Cannot install verified OCR model $lang" }
        }
    }
}

tasks.matching { it.name.matches(Regex("merge.+Assets")) }.configureEach {
    dependsOn(prepareLobbyOcrModels)
}

android {
    namespace = "com.example.airemote"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    sourceSets.getByName("main").assets.srcDir(lobbyOcrAssets)
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
    // Embedded loopback-only FTPS certificate/downgrade negative-handshake fixture.
    testImplementation("org.apache.ftpserver:ftpserver-core:1.2.1")
    // Browser-owned sign-in within the app; never collect ChatGPT passwords or 2FA codes.
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    // Offline OCR model; no Windows or Python service.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // OCR only for Russian/English CASH LOBBY labels and sort menu;
    // existing card recognizer and game-street ML Kit remain unchanged.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // T043 Stage 3: RFC 9180 HPKE + Ed25519; explicit FTPS only.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("commons-net:commons-net:3.13.0")
}

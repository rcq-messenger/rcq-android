import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing creds live in keystore.properties (gitignored) → the keystore
// itself sits outside the repo (~/.rcq/android-release). On a machine without
// it the config stays empty and only debug builds work.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "app.rcq.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.rcq.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "0.31"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // libsignal ships native .so for 4 ABIs; keep the real-device ones
        // (arm64-v8a, armeabi-v7a) + x86_64 for the emulator, drop 32-bit x86.
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8/minify stays OFF for now: the app leans on reflection (Gson over
            // the DTOs, libsignal/JNI) so enabling it needs a careful keep-rule
            // pass, and the savings are small next to the ~80MB of native libs
            // (libsignal + rcqbox) that R8 can't touch. Sign for release so the
            // APK installs + can self-update.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        // Required by libsignal-android; with
        // android.enableApiModelingAndGlobalSynthetics=true (gradle.properties)
        // this gives D8 the global-synthetics path to desugar its Java records.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true; buildConfig = true }

    // Split the APK by ABI so a device downloads only its own native libs.
    // libsignal + rcqbox (sing-box) each ship a ~40MB .so per ABI, so a
    // universal APK is ~3x heavier than any device needs — painful to
    // sideload over a slow/censored network (exactly our RU-tester case).
    // Per-ABI APKs land at app/build/outputs/apk/<type>/app-<abi>-<type>.apk.
    // universalApk stays on as a single-file fallback (and for the emulator
    // test flow). NB for a Play multi-APK upload each ABI needs a distinct
    // versionCode (an offset) — add that when/if we ship via Play; for
    // sideload it's irrelevant.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // libsignal_jni_testing.so is a ~80MB-per-ABI test-only native lib;
            // never ship it. (Halves the APK.)
            excludes += "**/libsignal_jni_testing.so"
            // Compress the native libs INSIDE the apk. Modern AGP defaults to
            // uncompressed (extractNativeLibs=false), which bloats the DOWNLOAD —
            // and libsignal_jni.so ships ~74MB of debug symbols that squeeze hard.
            // We distribute by direct sideload (no Play split-compression), and
            // the download size is the pain for users behind the relay, so trade
            // a little install footprint / first-load time for a much smaller
            // download. System.loadLibrary still works (libs extract on install).
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    // QR generation for the "my code" sheet (rcq://add/<uin>). Pure-Java
    // BitMatrix → Bitmap; no UI dependency.
    implementation(libs.zxing.core)

    // Networking (HTTP + WebSocket) and JSON — used by the API + WS
    // layers added in the next milestones.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // X25519 + Ed25519 keygen via the BouncyCastle lightweight API.
    // JCA's "XDH"/"Ed25519" KeyPairGenerators only exist on API 33+,
    // and minSdk is 26 — BC's generators work everywhere and give raw
    // 32-byte key access, which is exactly the wire format the backend
    // expects (base64 of raw public keys, per rcq-spec 2.2).
    implementation(libs.bouncycastle)

    // libsignal: Double Ratchet + PQXDH for v=2 forward secrecy (additive over
    // the v=1 ECIES sealed sender). Ships native .so per ABI in the .aar.
    implementation(libs.libsignal.android)

    // rcqbox: the embedded sing-box core (VLESS+Reality / Hysteria2) for the
    // censorship-circumvention transport — a gomobile-bound Go wrapper, same
    // Start/Stop API the iOS client uses. Built from ~/sing-box-src/rcqbox via
    // `gomobile bind -target=android/arm64,android/arm,android/amd64
    // -androidapi 26 -tags "with_utls,with_quic"`. Ships libgojni.so per ABI.
    implementation(files("libs/rcqbox.aar"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Encrypted-at-rest storage for the per-account identity (UIN, JWT,
    // private keys) — the Android equivalent of the iOS Keychain.
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.security.crypto)

    // SQLCipher: whole-DB encryption of the local message store under a
    // PIN-derived (or device) key for the panic-PIN at-rest protection.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // ProcessLifecycleOwner — re-lock the app when it goes to background.
    implementation(libs.androidx.lifecycle.process)

    // BiometricPrompt for the panic-PIN fingerprint/face unlock (phase 4).
    // Brings androidx.fragment in, so MainActivity is a FragmentActivity;
    // the explicit fragment pin overrides biometric-1.1.0's stale 1.2.5.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)

    // WebRTC (libwebrtc, org.webrtc.*) for 1:1 audio/video calls — same engine
    // the iOS client uses, signalling rides the existing WS dumb-relay.
    implementation(libs.stream.webrtc.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

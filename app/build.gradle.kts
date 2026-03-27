import java.net.URL

// libbox.aar (sing-box) replaces libXray.aar.
// Build libbox.aar from source using build_libbox.sh, or download a pre-built version.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jhopanstore.vpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jhopanstore.vpn"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0-singbox"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // No CMake/JNI needed — sing-box handles everything in-process via libbox.aar

    // ─── Build Flavors ────────────────────────────────────────────────────────
    // 'full'  → arm64-v8a + armeabi-v7a + x86_64 + x86  (phones + emulators)
    // 'phone' → arm64-v8a + armeabi-v7a only             (real phones only, smaller)
    flavorDimensions += "target"
    productFlavors {
        create("full") {
            dimension = "target"
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            }
        }
        create("phone") {
            dimension = "target"
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ─── Per-ABI APK Splits ───────────────────────────────────────────────────
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
}

dependencies {
    // libbox — sing-box core as in-process Go library (replaces libXray)
    implementation(files("libs/libbox.aar"))

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ═══════════════════════════════════════════════════════════════
//  Download libbox.aar from a configurable URL.
//  Run manually:  ./gradlew downloadLibboxAar
//
//  Config priority:
//   1. Env var LIBBOX_AAR_URL
//   2. gradle.properties: libboxAarUrl=https://...
//   3. Default: placeholder (user must build or set URL)
// ═══════════════════════════════════════════════════════════════
val defaultLibboxAarUrl = ""  // Set your URL here or in gradle.properties

tasks.register("downloadLibboxAar") {
    description = "Download libbox.aar into app/libs"
    group = "singbox"

    val libsDir = file("libs")
    val target = file("libs/libbox.aar")

    doLast {
        libsDir.mkdirs()
        if (target.exists() && target.length() > 0L) {
            println("✓  libbox.aar already present (${target.length() / 1024} KB)")
            return@doLast
        }

        val configuredUrl =
            System.getenv("LIBBOX_AAR_URL")
                ?: (findProperty("libboxAarUrl") as String?)
                ?: defaultLibboxAarUrl

        if (configuredUrl.isBlank()) {
            throw GradleException(
                """
                |libbox.aar not found in app/libs.
                |
                |To build from source:
                |  1. Run: bash build_libbox.sh
                |  2. Or download a pre-built AAR and place it in app/libs/libbox.aar
                |
                |To auto-download, set one of:
                |  - Env var: LIBBOX_AAR_URL=https://your-url/libbox.aar
                |  - gradle.properties: libboxAarUrl=https://your-url/libbox.aar
                """.trimMargin()
            )
        }

        println("⬇  Downloading libbox.aar from $configuredUrl …")

        try {
            val connection = URL(configuredUrl).openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.connect()
            connection.getInputStream().use { src ->
                target.outputStream().use { dst -> src.copyTo(dst) }
            }
        } catch (e: Exception) {
            throw GradleException(
                "Failed to download libbox.aar. Build from source with build_libbox.sh or set LIBBOX_AAR_URL.",
                e
            )
        }

        if (!target.exists() || target.length() == 0L) {
            throw GradleException("libbox.aar download produced an empty file")
        }

        println("✓  libbox.aar saved (${target.length() / 1024} KB)")
    }
}

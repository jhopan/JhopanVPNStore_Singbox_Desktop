import java.net.URL
import java.util.zip.ZipInputStream

// libXray.aar is downloaded on demand from GitHub Release assets.

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
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

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
            // Use debug signing for now (replace with proper keystore for production)
            signingConfig = signingConfigs.getByName("debug")
            // Strip debug symbols from native .so (default is RelWithDebInfo which bloats APK)
            externalNativeBuild {
                cmake {
                    arguments("-DCMAKE_BUILD_TYPE=Release")
                }
            }
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
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // ─── Per-ABI APK Splits ───────────────────────────────────────────────────
    // Combined with flavorDimensions above, Gradle produces per-ABI + per-flavor APKs:
    //
    // 'full' flavor  (assembleFullRelease):
    //   app-full-arm64-v8a-release.apk    → modern ARM64 phones (recommended)
    //   app-full-armeabi-v7a-release.apk  → older 32-bit ARM phones
    //   app-full-x86_64-release.apk       → x86_64 emulators / Chromebooks
    //   app-full-x86-release.apk          → legacy x86 emulators
    //   app-full-universal-release.apk    → ALL 4 ABIs bundled (universal-all)
    //
    // 'phone' flavor (assemblePhoneRelease):
    //   app-phone-arm64-v8a-release.apk   → arm64 only
    //   app-phone-armeabi-v7a-release.apk → armeabi only
    //   app-phone-universal-release.apk   → ARM-only universal (smaller, phone-focused)
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
    // libXray — Xray-core as in-process Go library (replaces xray binary)
    implementation(files("libs/libXray.aar"))

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
//  Download libXray.aar from GitHub Release assets.
// ═══════════════════════════════════════════════════════════════
val defaultLibXrayAarUrl = "https://github.com/jhopan/JhopanStoreVPN_Xray_APK/releases/download/binary-assets/libXray.aar"

tasks.register("downloadLibXrayAar") {
    description = "Download libXray.aar from GitHub releases into app/libs"
    group = "xray"

    val libsDir = file("libs")
    val target = file("libs/libXray.aar")

    doLast {
        libsDir.mkdirs()
        if (target.exists() && target.length() > 0L) {
            println("✓  libXray.aar already present (${target.length() / 1024} KB)")
            return@doLast
        }

        val configuredUrl =
            System.getenv("LIBXRAY_AAR_URL")
                ?: (findProperty("libXrayAarUrl") as String?)
                ?: defaultLibXrayAarUrl

        println("⬇  Downloading libXray.aar from $configuredUrl …")

        try {
            val connection = URL(configuredUrl).openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.connect()
            connection.getInputStream().use { src ->
                target.outputStream().use { dst -> src.copyTo(dst) }
            }
        } catch (e: Exception) {
            throw GradleException(
                "Failed to download libXray.aar. Upload it once to a GitHub Release and set LIBXRAY_AAR_URL if needed.",
                e
            )
        }

        if (!target.exists() || target.length() == 0L) {
            throw GradleException("libXray.aar download produced an empty file")
        }

        println("✓  libXray.aar saved (${target.length() / 1024} KB)")
    }
}

// ═══════════════════════════════════════════════════════════════
//  Download tun2socks binary (bridges TUN ↔ SOCKS5 proxy).
//  Run manually:  ./gradlew downloadTun2socks
// ═══════════════════════════════════════════════════════════════
val tun2socksVersion = "v2.6.0"

tasks.register("downloadTun2socks") {
    description = "Download tun2socks binary into jniLibs (prefer project release assets)"
    group = "xray"

    val jniLibsDir = file("src/main/jniLibs")

    doLast {
        mapOf(
            "arm64-v8a" to "linux-arm64",
            "armeabi-v7a" to "linux-armv7"
        ).forEach { (abi, archName) ->
            val dir = File(jniLibsDir, abi).also { it.mkdirs() }
            val target = File(dir, "libtun2socks.so")

            if (target.exists()) {
                println("✓  tun2socks $abi already present (${target.length() / 1024} KB)")
                return@forEach
            }

            val envVarName = if (abi == "arm64-v8a") "TUN2SOCKS_ARM64_URL" else "TUN2SOCKS_ARMV7_URL"
            val overrideUrl =
                System.getenv(envVarName)
                    ?: (findProperty("tun2socks.${abi}.url") as String?)
            val fallbackZipUrl = "https://github.com/xjasonlyu/tun2socks/releases/download/$tun2socksVersion/tun2socks-$archName.zip"

            try {
                val sourceUrl = overrideUrl ?: fallbackZipUrl
                println("⬇  Downloading tun2socks $abi from $sourceUrl …")
                val connection = URL(sourceUrl).openConnection()
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.connect()
                if (overrideUrl != null) {
                    connection.getInputStream().use { src ->
                        target.outputStream().use { dst -> src.copyTo(dst) }
                    }
                    target.setExecutable(true)
                    println("✓  tun2socks $abi saved (${target.length() / 1024} KB)")
                } else {
                    val tmp = File.createTempFile("tun2socks-$abi", ".zip")
                    connection.getInputStream().use { src ->
                        tmp.outputStream().use { dst -> src.copyTo(dst) }
                    }

                    var found = false
                    ZipInputStream(tmp.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.startsWith("tun2socks")) {
                                target.outputStream().use { out -> zis.copyTo(out) }
                                found = true
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                    tmp.delete()

                    if (found) {
                        target.setExecutable(true)
                        println("✓  tun2socks $abi saved (${target.length() / 1024} KB)")
                    } else {
                        println("✗  tun2socks binary not found in zip for $abi")
                    }
                }
            } catch (e: Exception) {
                println("✗  Primary source failed for $abi: ${e.message}")
                println("⬇  Fallback to upstream tun2socks release: $fallbackZipUrl …")

                try {
                    val tmp = File.createTempFile("tun2socks-$abi", ".zip")
                    val connection = URL(fallbackZipUrl).openConnection()
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 120_000
                    connection.connect()
                    connection.getInputStream().use { src ->
                        tmp.outputStream().use { dst -> src.copyTo(dst) }
                    }

                    var found = false
                    ZipInputStream(tmp.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.startsWith("tun2socks")) {
                                target.outputStream().use { out -> zis.copyTo(out) }
                                found = true
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                    tmp.delete()

                    if (found) {
                        target.setExecutable(true)
                        println("✓  tun2socks $abi saved (${target.length() / 1024} KB)")
                    } else {
                        println("✗  tun2socks binary not found in fallback zip for $abi")
                    }
                } catch (fallbackError: Exception) {
                    println("✗  Failed to download tun2socks $abi: ${fallbackError.message}")
                    println("   The app will try downloading at runtime instead.")
                }
            }
        }
    }
}

// Automatically download binary dependencies before build
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadLibXrayAar")
    dependsOn("downloadTun2socks")
}

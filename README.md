# JhopanStoreVPN (Android)

Android VPN client berbasis VLESS + Xray dengan UI Jetpack Compose.

## Ringkasan Aplikasi
- Protokol utama: VLESS (Xray core di-embed via `libXray.aar`).
- Service: VPN foreground service dengan auto reconnect, ping monitor, wake lock opsional, dan notifikasi status.
- Target perangkat: Android API 24+.

## Arsitektur Binary Dependency
Repository source tidak perlu menyimpan binary besar.

- `libXray.aar` diambil dari GitHub Release tag `binary-assets`.
- `libtun2socks.so` default diambil dari upstream tun2socks release.
- Jika dibutuhkan, tun2socks bisa dioverride ke URL release milik sendiri.

Dengan model ini, source repo tetap ringan tapi build lokal/CI tetap jalan.

## Cara Build Lokal
Jalankan dari folder `android/`:

```bash
./gradlew assemblePhoneRelease
./gradlew assembleFullRelease
```

Task `preBuild` otomatis memanggil:
- `downloadLibXrayAar`
- `downloadTun2socks`

## Override URL Binary Saat Lokal
Jika ingin pakai URL custom (misalnya release private/fork), bisa override:

```bash
set LIBXRAY_AAR_URL=https://github.com/<owner>/<repo>/releases/download/binary-assets/libXray.aar
set TUN2SOCKS_ARM64_URL=https://github.com/<owner>/<repo>/releases/download/binary-assets/libtun2socks-arm64-v8a.so
set TUN2SOCKS_ARMV7_URL=https://github.com/<owner>/<repo>/releases/download/binary-assets/libtun2socks-armeabi-v7a.so
./gradlew assemblePhoneRelease
```

Alternatif via Gradle property:

```bash
./gradlew assemblePhoneRelease -PlibXrayAarUrl=https://...
./gradlew assemblePhoneRelease -Ptun2socks.arm64-v8a.url=https://...
./gradlew assemblePhoneRelease -Ptun2socks.armeabi-v7a.url=https://...
```

## Output APK Lokal
- `app/build/outputs/apk/phone/release/app-phone-arm64-v8a-release.apk`
- `app/build/outputs/apk/phone/release/app-phone-armeabi-v7a-release.apk`
- `app/build/outputs/apk/phone/release/app-phone-universal-release.apk`
- `app/build/outputs/apk/full/release/app-full-x86_64-release.apk`
- `app/build/outputs/apk/full/release/app-full-universal-release.apk`

## CI dan Release
Workflow: `.github/workflows/build.yaml`

Alur CI:
- Build APK untuk target ABI utama.
- Upload artifact run dengan nama prefix `JhopanStoreVPN-*`.
- Saat push tag `v*`, release akan dipublish berisi:
	- APK dengan nama `JhopanStoreVPN-...`
	- `JhopanStoreVPN-libXray.aar`

## Alur Update Binary Sekali Upload
Jika suatu saat mau ganti libXray/tun2socks:

1. Upload asset baru ke release tag `binary-assets` (replace file lama).
2. Jalankan build ulang (lokal/CI otomatis ambil binary terbaru).
3. Tidak perlu commit binary ke source repo.

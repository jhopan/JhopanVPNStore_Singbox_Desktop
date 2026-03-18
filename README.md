# JhopanStoreVPN Xray APK (Android)

Android VPN client using VLESS + Xray core via `libXray.aar`.

## Version
- `versionName`: `1.0.0`

## Build Output Targets
This project produces release APKs for:
- `arm64-v8a` (v8a)
- `armeabi-v7a` (arm/v7)
- `x86_64` (amd)
- `full universal` (all ABIs)
- `phone universal` (android universal)

## Local Build
From `android/`:

```bash
./gradlew assemblePhoneRelease
./gradlew assembleFullRelease
```

## Main APK Paths
- `app/build/outputs/apk/phone/release/app-phone-arm64-v8a-release.apk`
- `app/build/outputs/apk/phone/release/app-phone-armeabi-v7a-release.apk`
- `app/build/outputs/apk/phone/release/app-phone-universal-release.apk`
- `app/build/outputs/apk/full/release/app-full-x86_64-release.apk`
- `app/build/outputs/apk/full/release/app-full-universal-release.apk`

## CI Workflow
GitHub Actions workflow file:
- `.github/workflows/build.yaml`

It now does this flow:
- One-time publish binary dependencies to release tag `binary-assets` (if not exists yet).
- Build APKs using binary from that release.
- Upload APK artifacts on each run.
- On version tags (`v*`), also publish APK + `libXray.aar` into GitHub Release.

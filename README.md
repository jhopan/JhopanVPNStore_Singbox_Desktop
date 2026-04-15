<div align="center">

# 🛡️ JhopanStoreVPN Singbox (v1.0.0)

**Klien VPN Android Modern Berbasis VLESS + Sing-box**

<p align="center">
  <img src="https://img.shields.io/badge/Android-24%2B-3DDC84?logo=android&logoColor=white" alt="Android API 24+"/>
  <img src="https://img.shields.io/badge/Kotlin-1.9.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Jetpack_Compose-UI-4285F4?logo=android&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/Sing_box-v1.11.0-009688" alt="Sing-box v1.11.0"/>
  <img src="https://img.shields.io/github/v/release/jhopan/JhopanStoreVPN_Singbox?color=blue&label=Release" alt="Release"/>
</p>

Aplikasi VPN Android **full-featured** yang mengutamakan:

- 🔥 **Performa maksimal** dengan core sing-box native (Go engine)
- 🛡️ **Keamanan tingkat sistem** via TUN interface
- ⚙️ **Kontrol penuh** atas routing & rule blocking
- 🧬 **Hemat daya** dengan zero background overhead
- ✨ **UI/UX modern** pure Jetpack Compose

---

</div>

## 📑 Daftar Isi

- [🚀 Fitur Utama](#-fitur-utama)
- [📦 Arsitektur & Teknologi](#-arsitektur--teknologi)
- [📥 Download & Instalasi Cepat](#-download--instalasi-cepat)
- [🛠️ Build Sendiri (Local)](#️-build-sendiri-local)
- [🔄 GitHub Release & Auto-Build Pipeline](#-github-release--auto-build-pipeline)
- [📋 Spesifikasi Aplikasi](#-spesifikasi-aplikasi)

---

## 🚀 Fitur Utama

### ⚡ Performa & Koneksi

- **Core Sing-box Native VLESS**: Ditenagai v1.11.0 dengan libbox Go native untuk koneksi VLESS super responsif dan tahan DPI
- **TUN Interface Kernel**: Routing transparan di level kernel tanpa proxy overhead — **tidak perlu root!**
- **Zero Compression Overhead**: Direct kernel routing = performa maksimal, battery friendly

### 🔄 Sistem Pemulihan Otonom

- **Auto-Reconnect Pintar**: Deteksi koneksi drop otomatis dan reconnect instant
- **Max Retry Control**: Atur sendiri batasan retry attempts
- **Smart Delay**: Konfigurasi delay antar reconnect untuk efisiensi baterai

### 📡 Monitoring & Stabilitas

- **Real-Time Ping Monitor**: Pantau latensi koneksi secara live
- **Live Usage Statistics**: Download/upload real-time di app dan notification
- **Foreground Service**: VPN dibungkus dalam notifikasi persisten (sulit di-kill)
- **Wake Lock Opsional**: Jaga koneksi aktif saat layar dikunci (bisa disable)

### 🚫 Custom Rule Management

- **Manual Rule Import**: Import rules JSON dari GitHub atau custom server
- **Smart Rule Injection**: Rules otomatis inject saat connect
- **Flexible Routing**: Ubah target (reject/direct/main/backup) kapan saja
- **Click-Apply**: Apply rule saat VPN running untuk instant effect

### 🎨 UI/UX Futuristik

- **100% Jetpack Compose**: Antarmuka modern dengan animasi smooth
- **Responsive Design**: Sempurna di semua ukuran layar
- **Dark/Light Mode**: Dukungan theme otomatis sesuai sistem

---

## 📦 Arsitektur & Teknologi

| Komponen         | Detail                                      |
| ---------------- | ------------------------------------------- |
| **VPN Engine**   | Sing-box v1.11.0 (native Go via libbox.aar) |
| **Protocol**     | VLESS (WebSocket + TLS)                     |
| **Routing**      | Kernel TUN Interface (0% root required)     |
| **UI Framework** | Jetpack Compose Material3                   |
| **Language**     | Kotlin 100%                                 |
| **Min OS**       | Android 7.0+ (API 24)                       |
| **Target OS**    | Android 14+ (API 34)                        |
| **APK Size**     | v8a: ~22 MB, v7a: ~20 MB, Universal: ~41 MB |
| **Battery**      | ✅ Optimal (native + smart job management)  |

### Mengapa Sing-box, bukan Xray?

✅ **Native Go Performance** - Langsung Go binary, bukan JNI wrapper  
✅ **Lighter Footprint** - Minimal CPU & memory usage  
✅ **Better Protocol Support** - VLESS, Shadowsocks, Trojan, DNS filtering  
✅ **Modern Maintenance** - Active development dengan update berkala  
✅ **Production Ready** - Trusted oleh ribuan VPN provider

---

## 📥 Download & Instalasi Cepat

### 🚀 Ingin Langsung Pakai? (Tidak perlu build)

1. Buka [**Releases**](https://github.com/jhopan/JhopanStoreVPN_Singbox/releases) di GitHub
2. Download APK sesuai device:
   - **`arm64-v8a.apk`** ← **[REKOMENDASI]** Untuk 99% Android modern
   - **`armeabi-v7a.apk`** ← Untuk device 32-bit lama
   - **`universal.apk`** ← Untuk emulator & semua device sekaligus

3. Install:

```bash
adb install -r JhopanStoreVPN_Singbox_arm64-v8a.apk
```

### 📦 Dari Mana AAR-nya?

**libbox.aar** (sing-box native) diambil dari:

```
https://github.com/jhopan/Build/releases/download/v2.0.2-diet/libbox.aar
```

- **Auto-download**: Saat build GitHub Actions atau local, libbox auto-fetch dari URL di atas
- **Manual**: Letakkan file `.aar` di `app/libs/` jika pakai custom version
- **Environment Variable**: Override dengan `LIBBOX_AAR_URL` saat build

---

## 🛠️ Build Sendiri (Local)

### Persyaratan

- JDK 17+ (OpenJDK 17 atau Temurin 17)
- Android SDK API 34+ (via Android Studio atau CLI)
- Gradle Wrapper (sudah ada di repo)
- Internet stabil (untuk download dependencies)

### Langkah-Langkah

1. **Clone repository:**

```bash
git clone https://github.com/jhopan/JhopanStoreVPN_Singbox.git
cd JhopanStoreVPN_Singbox
```

2. **Build APK (Gradle akan auto-download libbox.aar):**

```bash
# Build untuk Android devices (ARM64 — recommended)
./gradlew assemblePhoneRelease -x test

# Atau build universal (emulator + all architectures)
./gradlew assembleFullRelease -x test
```

3. **APK ready di:**

```
app/build/outputs/apk/phone/release/app-phone-arm64-v8a-release.apk
```

4. **Install:**

```bash
adb install -r app/build/outputs/apk/phone/release/app-phone-arm64-v8a-release.apk
```

### ⏱️ Durasi Build

- **First build**: 2-3 menit (GradleWrapper & libraries download)
- **Subsequent**: 1-2 menit (cache ada)

### Custom AAR URL (Opsional)

Jika punya private AAR server:

```bash
export LIBBOX_AAR_URL=https://your-server.com/libbox.aar
./gradlew assemblePhoneRelease
```

---

## 🔄 GitHub Release & Auto-Build Pipeline

Repository ini dilengkapi **GitHub Actions workflow** untuk build & release otomatis.

### Alur Kerja

1. **Push ke `main`**: Setiap push menjalankan build validation
2. **Create Git Tag** (e.g., `git tag -a v1.0.0`): Pipeline otomatis build APK
3. **GitHub Release Created**: Ketiga APK terupload terpisah (bukan ZIP)
4. **Download**: User bisa ambil APK langsung dari [Releases](https://github.com/jhopan/JhopanStoreVPN_Singbox/releases)

### Workflow Files

- **`.github/workflows/build-app.yml`** → Build validation setiap push
- **`.github/workflows/release.yml`** → Auto-release saat ada Git tag

---

## 📋 Spesifikasi Aplikasi

### Main Screen

- ✅ **Connect/Disconnect Button** dengan status visual
- ✅ **Live Ping Display** (ms latency)
- ✅ **Real-time Data Usage** (upload/download stats)
- ✅ **Connection Status** (Connected, Connecting, Disconnected)

### Settings

- ✅ **Server Configuration** (address, port, protocol)
- ✅ **DNS Settings** (default, custom, CloudFlare, etc)
- ✅ **MTU Configuration** (network tweaking)
- ✅ **Auto-Reconnect Options** (enable/disable, max retry)
- ✅ **Keep-Alive Mode** (background persistence)
- ✅ **Wake Lock** (prevent sleep during VPN)

### Rules Management

- ✅ **Import Custom Rules** (JSON format)
- ✅ **Manage Rules** (enable/disable/delete)
- ✅ **Apply Rules on Connect** (dynamic routing)
- ✅ **Routing Targets** (main, backup, direct, reject)

### Advanced Features

- ✅ **Multi-Server Backup** (failover support)
- ✅ **Account Auto-Switcher** (rotate servers)
- ✅ **Traffic Analytics** (real-time monitoring)
- ✅ **Notification Stats** (persistent HUD)

---

## 🤝 Kontribusi & Support

- **Bug Reports**: Issue tracker di GitHub
- **Feature Requests**: Discussion thread
- **Pull Requests**: Sangat diterima!
- **Fork & Modify**: Lisensi memperbolehkan

---

## 📄 License

**MIT License** - Dibuat dengan ❤️ untuk kebebasan & keamanan online.

```
Copyright (c) 2024 Jhopan
Permission is hereby granted, free of charge...
```

---

## 🔗 Links

| Link                                                                        | Purpose         |
| --------------------------------------------------------------------------- | --------------- |
| [Releases](https://github.com/jhopan/JhopanStoreVPN_Singbox/releases)       | Download APK    |
| [Issues](https://github.com/jhopan/JhopanStoreVPN_Singbox/issues)           | Bug Report      |
| [Discussions](https://github.com/jhopan/JhopanStoreVPN_Singbox/discussions) | Feature Request |
| [libbox Source](https://github.com/jhopan/Build)                            | Sing-box Binary |

---

**Version**: v1.0.0 ✨  
**Last Updated**: April 10, 2026  
**Status**: Stable Release

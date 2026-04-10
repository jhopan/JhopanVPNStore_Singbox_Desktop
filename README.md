<div align="center">

# 🛡️ JhopanStoreVPN (Android)

**Klien VPN Android Next-Gen Berbasis VLESS + Sing-box**

<p align="center">
  <img src="https://img.shields.io/badge/Android-24%2B-3DDC84?logo=android&logoColor=white" alt="Android API 24+"/>
  <img src="https://img.shields.io/badge/Kotlin-1.9.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Jetpack_Compose-UI-4285F4?logo=android&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/Sing_box-v1.11.0-009688?logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0xMiAyQzYuNDggMiAyIDYuNDggMiAxMnM0LjQ4IDEwIDEwIDEwIDEwLTQuNDggMTAtMTBTMTcuNTIgMiAxMiAyem0wIDE4Yy00LjQxIDAtOC0zLjU5LTgtOHMzLjU5LTggOC04IDggMy41OSA4IDgtMy41OSA4LTggOHptMy41LTljLjgzIDAgMS41LS42NyAxLjUtMS41cy0uNjctMS41LTEuNS0xLjUtMS41LjY3LTEuNSAxLjUuNjcgMS41IDEuNSAxLjV6Ii8+PC9zdmc+" alt="Sing-box v1.11.0"/>
  <img src="https://img.shields.io/github/v/release/jhopan/JhopanStoreVPN_Xray_APK?color=blue&label=Latest%20Release" alt="Release"/>
</p>

Aplikasi VPN Android **full-featured** yang mengutamakan:

- 🔥 **Performa maksimal** dengan core sing-box native (Go engine)
- 🛡️ **Keamanan tingkat sistem** via TUN interface (tanpa proxy overhead)
- ⚙️ **Kontrol penuh** atas routing & rule blocking (pornografi, malware, dll)
- 🧬 **Arsitektur hemat daya** dengan zero background overhead
- ✨ **UI/UX modern** pure Jetpack Compose

Dirancang dengan antarmuka **Jetpack Compose** murni, arsitektur _binary dependency_ revolusioner, dan mengutamakan stabilitas koneksi tingkat tinggi di berbagai kondisi jaringan.

---

</div>

## 📑 Daftar Isi

- [🚀 Fitur Utama](#-fitur-utama)
- [📦 Arsitektur Binary & Performa](#-arsitektur-binary--performa)
- [🛠️ Cara Build Sendiri (Local)](#️-cara-build-sendiri-local)
- [🧹 Cleanup Cache & Rebuild](#-cleanup-cache--rebuild)
- [⚙️ Pengaturan URL Lanjutan](#️-pengaturan-url-lanjutan)
- [🔄 CI/CD & Pipeline Release](#-cicd--pipeline-release)

---

## 🚀 Fitur Utama

Aplikasi ini tidak sekadar VPN biasa; ini dirancang dengan berbagai fitur tingkat lanjut untuk memastikan **stabilitas, keamanan, dan kenyamanan maksimum** saat berselancar di internet:

### ⚡ Performa & Koneksi

- **Core Sing-box Native VLESS**: Ditenagai langsung oleh arsitektur sing-box v1.11.0 (via `libbox` Go native) untuk menyediakan koneksi protokol VLESS yang sangat responsif, cepat, dan tangguh terhadap _Deep Packet Inspection_ (DPI).
- **TUN Interface Kernel**: Menggunakan kernel-level TUN interface untuk menangkap dan merutekan seluruh lalu lintas aplikasi di sistem Android secara transparan dan efisien. **Tidak perlu akses root!**

### 🔄 Sistem Pemulihan Otonom

Mencegah pemutusan jaringan yang mengganggu akibat transisi sinyal (misalnya dari Wi-Fi ke Seluler):

- **Auto-Reconnect Pintar**: Mekanisme koneksi ulang otomatis yang 100% bisa dikustomisasi.
- **Max Retry Control**: Tentukan sendiri batasan maksimal sistem mencoba menghubungkan ulang.
- **Delay Asinkron**: Sesuaikan jeda waktu (_Reconnect Delay_) antar percobaan ulang untuk menjaga baterai.

### 📡 Monitoring & Stabilitas

- **Real-Time Ping Monitor**: Pantau selalu kualitas sambungan (latensi) secara _live_ di layar.
- **Live Usage Statistics**: Tampilkan statistik download/upload real-time di app dan notification.
- **Stabilitas Background Prioritas Tertinggi**:
  - **Foreground Service**: Membungkus mesin VPN dalam layanan dengan _Notifikasi Status_ persisten. Sangat sulit di-kill sistem.
  - **Wake Lock (Opsional)**: Jaga koneksi tetap aktif saat layar dikunci.

### 🚫 Custom Rule Management (BARU!)

- **Manual Rule Import**: Import rule JSON dari GitHub (RouteRules/Rule files)
- **Smart Rule Injection**: Rules auto-inject saat connect, tanpa perlu manual apply
- **Flexible Routing**: Ubah target outbound (reject/direct/main/backup) kapan saja
- **Edit & Update**: Re-import file untuk update rule tanpa delete
- **Click-Apply Style**: Apply rule saat VPN running untuk instant effect

### 🎨 Desain Modern

- **UI/UX Futuristik**: Antarmuka dibangun _from scratch_ menggunakan **Jetpack Compose**, menyuguhkan animasi yang mulus, respon sentuh seketika, dan kompatibilitas tata letak sempurna di semua ukuran layar.

---

## 📦 Arsitektur Binary & Performa

Kami memahami bahwa ukuran repositori yang membengkak adalah mimpi buruk bagi developer. Oleh karena itu, JhopanStoreVPN menggunakan **Arsitektur Binary Ramping**:

1. **Anti-Bloatware Repo**: File `.aar` dan `.so` berukuran raksasa tidak diunggah ke dalam repositori utama.
2. **On-Demand Download** (jika diperlukan):
   - `libbox.aar` (sing-box go native) tersimpan di `app/libs/`
   - Atau diunduh otomatis dari release yang dikonfigurasi
3. **Build Varian Diferensiasi**:
   - **`phone`**: Cepat dan ringan, dioptimalkan untuk ARM modern (v8a).
   - **`full`**: Versi universal yang mencakup `x86_64` untuk emulasi dan kompatibilitas luas.

Output _build_ lokal akan otomatis dipilah sesuai foldernya:

```text
app/build/outputs/apk/{varian}/release/app-{varian}-{arsitektur}-release.apk
```

### Keunggulan Sing-box Native:

✅ **Langsung Go Runtime** - Bukan JNI wrapper, langsung native binary  
✅ **Hemat Daya** - Zero overhead, CPU efficiency maksimal  
✅ **Kecepatan Tinggi** - Protocol handling murni di level kernel  
✅ **Stable** - Production-grade v1.11.0

---

## 🛠️ Cara Build Sendiri (Local)

Ingin nge-build dan memodifikasi proyek ini sendiri? Sangat mudah! Semuanya sudah diotomatisasi lewat skrip build Gradle.

**💻 Persyaratan Awal:**

- Koneksi internet stabil
- JDK 17+ (direkomendasikan)
- Android Studio / Command Line Tools terbaru

**Langkah-langkah Build:**

1. Clone repositori ini:

```bash
git clone https://github.com/jhopan/JhoVPN.git
cd JhoVPN/android2
```

2. Buka terminal lalu eksekusi _Gradle Wrapper_:

```bash
# Build untuk perangkat HP nyata (ARM 64-bit) — REKOMENDASI
./gradlew.bat assemblePhoneRelease -x test

# Atau build varian lengkap untuk Universal/Emulator
./gradlew.bat assembleFullRelease -x test
```

3. APK siap di:

```
app/build/outputs/apk/phone/release/app-phone-arm64-v8a-release.apk
```

> **✨ Gradle Magic:** Semua dependency dan library akan di-download otomatis saat build pertama kali. Anda tidak perlu setup manual apapun!

---

## 🧹 Cleanup Cache & Rebuild

Repositori ini telah dibersihkan dari folder-folder cache besar yang tidak diperlukan untuk VCS (Version Control System).

**Folder yang dihapus:**

```
❌ .gradle/         → Gradle dependency cache (~200MB+)
❌ .idea/            → Android Studio IDE cache (~50MB+)
❌ app/build/        → Build artifacts & intermediate files (~500MB+)
❌ app/.cxx/         → CMake/NDK cache (~100MB+)
```

**Mengapa aman untuk dihapus?**

- ✅ **BUKAN source code** — Semua file `.kt`, `.java`, `res/`, `src/` tetap aman
- ✅ **BUKAN library penting** — `app/libs/libbox.aar` tetap ada
- ✅ **Regenerate otomatis** — Gradle akan otomatis membuat ulang saat build
- ✅ **Menghemat GB space** — Total ukuran folder berkurang drastis

**Dapat dikembalikan? YA, tapi tidak perlu!**

Folder-folder cache dapat dikembalikan dengan REBUILD LOCAL. Desktop Anda tidak perlu menyimpan megabytes garbage yang bisa dibuat ulang dalam hitungan menit.

**Rebuild After Cleanup (Local):**

```bash
# Navigate ke project root
cd c:\Users\ACER\Documents\Project\Aplikasi\JhoVPN\android2

# Rebuild untuk perangkat real (ARM 64-bit)
.\gradlew.bat assemblePhoneRelease -x test

# Atau rebuild full untuk emulator/universal
.\gradlew.bat assembleFullRelease -x test
```

**APK Output Location:**

```
app/build/outputs/apk/phone/release/app-phone-arm64-v8a-release.apk
```

**Install ke device:**

```bash
adb install -r "app/build/outputs/apk/phone/release/app-phone-arm64-v8a-release.apk"
```

> **⏱️ Durasi Build:**
>
> - First build: **2-3 menit** (download library)
> - Subsequent builds: **1-2 menit** (cache ada)

---

## ⚙️ Pengaturan URL Lanjutan (Opsional)

Punya server aset _private_ sendiri? Anda bisa menyesuaikan dari mana unduhan file _binary_ berasal dengan _Environment Variables_:

**Via Terminal (Environment):**

```bash
set LIBBOX_AAR_URL=https://github.com/<owner>/<repo>/.../libbox.aar

./gradlew.bat assemblePhoneRelease
```

---

## 🔄 CI/CD & Pipeline Release

Proyek ini telah dilengkapi dengan GitHub Actions workflow untuk build dan release otomatis.

**Alur Kerja Otomatis:**

1. Setiap _push_ akan menjalankan build validation
2. Tag release (v*.*.\* ) akan otomatis menghasilkan APK siap pakai
3. File APK tersedia di GitHub Releases

---

## 📋 APP SPECS

| Aspek           | Detail                           |
| --------------- | -------------------------------- |
| **Engine**      | Sing-box v1.11.0 (native Go)     |
| **Protocol**    | VLESS (WebSocket + TLS)          |
| **Routing**     | Kernel TUN Interface             |
| **Min Android** | API 24+ (Android 7.0+)           |
| **App Size**    | ~23-25 MB                        |
| **Battery**     | ✅ Hemat (native engine)         |
| **Permission**  | VPN service only, no root needed |
| **UI**          | 100% Jetpack Compose             |

---

## 🎯 Use Cases

**1. Privacy & Security**

```
User → JhoVPN → sing-box → VLESS server
              ↓
        (TUN encryption)
              ↓
        Internet (safe)
```

**2. Content Blocking (Rules)**

```
Req: pornhub.com → Matched rule → REJECT
Req: google.com  → No rule     → DIRECT
```

**3. Smart Failover**

```
Primary server down → Auto-detect (ping test)
                   → Switch to backup instantly
                   → No app restart needed
```

---

## 📱 Screenshots & Features

- **Main Screen**: Connect/Disconnect + Live Ping + Data Usage
- **Settings**: DNS, MTU, Auto-reconnect, Keep-alive options
- **Rules Tab**: Import custom rules, manage routing targets
- **Backup Accounts**: Multiple server support with auto-failover

---

## 🤝 Kontribusi

Bug reports, feature requests, dan PR sangat diterima!

Untuk development guidelines: Lihat `BUILD_FROM_BACKUP.md`

---

## 📄 License

Dibuat dengan ❤️ untuk kebebasan dan keamanan online.

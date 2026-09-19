# Caddy Android HTTPS Reverse Proxy (DuckDNS DNS-01)

Solusi HTTPS Reverse Proxy otomatis di Android untuk memungkinkan akses **HTML5 Camera API (`getUserMedia`)** dari perangkat tethering (client) melalui domain `https://tjipto.duckdns.org:8443` yang diteruskan ke aplikasi lokal di `127.0.0.1:8090`.

---

## 🎯 Mengapa Membutuhkan Ini?
* Browser modern (Chrome, Safari, Firefox, Edge) mewajibkan **Secure Context (HTTPS)** untuk mengizinkan akses ke Camera/Microphone API.
* Pengecualian `http://localhost` hanya berlaku bila browser dibuka langsung di perangkat yang sama.
* Bila perangkat lain terhubung via **WiFi Hotspot / Tethering** (misal IP `192.168.43.x`), koneksi HTTP biasa dianggap **insecure** sehingga kamera **ditolak** oleh browser.
* Dengan project ini, Caddy mengambil sertifikat SSL Let's Encrypt resmi dan valid secara gratis melalui **DuckDNS DNS-01 Challenge** (tanpa perlu membuka port 80/443 ke internet publik).

---

## 🚀 Solusi Build: 0 MB Compiler di Laptop Anda!
Sesuai permintaan Anda untuk **tidak menginstal compiler/SDK Android yang berat** (seperti Android Studio ~15-30 GB), kami telah menyiapkan sistem **Cloud CI/CD Build**:

### Opsi 1: Build APK via GitHub Actions (Sangat Direkomendasikan - 0 MB di Laptop)
Project ini sudah dilengkapi file workflow [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).

1. Inisialisasi Git dan push folder `d:\AIDEV\caddy` ke repository GitHub Anda (Public maupun Private):
   ```powershell
   git init
   git add .
   git commit -m "Initial Caddy Android Proxy APK project"
   git branch -M main
   git remote add origin https://github.com/USERNAME/REPO_NAME.git
   git push -u origin main
   ```
2. Buka tab **Actions** di repository GitHub Anda.
3. GitHub Actions akan otomatis meng-compile APK menggunakan cloud runner (Google Android SDK + OpenJDK resmi dari GitHub).
4. Setelah selesai (~1-2 menit), download file **`CaddyProxy-Debug-APK.zip`** dari bagian **Artifacts**.
5. Ekstrak dan instal file `.apk` ke ponsel Android Anda!

---

### Opsi 2: Uji Coba Cepat Tanpa APK (Menggunakan Binary yang Sudah Jadi)
Binary Caddy ARM64 dengan DuckDNS plugin sudah **berhasil dikompilasi** di laptop Anda:
📁 Lokasi: `d:\AIDEV\caddy\caddy-android-arm64`

Jika Anda ingin langsung mengujinya di Android tanpa APK:
1. Salin file `caddy-android-arm64` dan file `scripts/Caddyfile.example` ke HP Android.
2. Di aplikasi **Termux** pada Android:
   ```bash
   chmod +x caddy-android-arm64
   ./caddy-android-arm64 run --config Caddyfile.example
   ```

---

## 📱 Cara Menggunakan Aplikasi di Android

1. **Nyalakan Tethering / Hotspot Portabel** di HP Android Anda.
2. Buka aplikasi **Caddy Reverse Proxy**.
3. Masukkan / sesuaikan parameter pada Textbox yang tersedia:
   * **DuckDNS Domain**: `tjipto.duckdns.org` (Domain terdaftar di DuckDNS)
   * **DuckDNS Token**: Token API rahasia Anda dari www.duckdns.org
   * **Backend Target Host**: `127.0.0.1` (IP target aplikasi lokal)
   * **Backend Target Port**: `8090` (Port target aplikasi kamera/PocketBase/web Anda)
   * **HTTPS Listen Port**: `8443` (Port HTTPS yang didengarkan oleh Caddy)
   * **Hotspot IP Override**: *(Opsional)* Kosongkan untuk auto-detect (`192.168.43.1`) atau isi manual jika hotspot memakai subnet berbeda.
4. Klik **Simpan Pengaturan** untuk menyimpan ke penyimpanan internal Android (`SharedPreferences`), sehingga saat aplikasi dibuka kembali seluruh parameter tidak akan hilang.
5. Klik tombol **Start Caddy Proxy**.
   * Aplikasi akan mendeteksi IP Hotspot HP (biasanya `192.168.43.1`).
   * Aplikasi otomatis mengupdate DNS DuckDNS agar domain `tjipto.duckdns.org` mengarah ke IP hotspot tersebut.
   * Caddy Foreground Service akan berjalan di background dengan notifikasi status.
   * Sertifikat Let's Encrypt divalidasi via DuckDNS TXT record (DNS-01).

---

## 💻 Cara Mengakses dari Perangkat Client (Laptop / Tablet)

1. Hubungkan WiFi perangkat client ke Hotspot HP Android Anda.
2. Buka browser di client, ketik:
   ```
   https://tjipto.duckdns.org:8443
   ```
3. Browser akan mendeteksi koneksi **HTTPS valid dengan gembok hijau/aman**.
4. Fitur **HTML5 Camera API** (`navigator.mediaDevices.getUserMedia`) akan langsung aktif dan diizinkan tanpa error keamanan!

---

## ⚙️ Struktur Project

```
d:\AIDEV\caddy/
├── .github/workflows/
│   └── build-apk.yml               # Otomasi build APK di GitHub Cloud (0 MB di laptop)
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml     # Izin INTERNET, FOREGROUND_SERVICE, WAKE_LOCK
│   │   ├── java/com/caddy/proxy/
│   │   │   ├── MainActivity.kt     # Tampilan konfigurasi, status, log & auto-save
│   │   │   ├── CaddyService.kt     # Foreground Service pengelola proses Caddy
│   │   │   ├── DuckDnsHelper.kt    # Auto-update IP DuckDNS
│   │   │   └── NetworkHelper.kt    # Detektor IP Hotspot (192.168.43.1)
│   │   ├── jniLibs/arm64-v8a/
│   │   │   └── libcaddy.so         # Binary Caddy ARM64 + DuckDNS (native executable)
│   │   └── res/                    # Tampilan UI & Resources
│   └── build.gradle.kts
├── scripts/
│   ├── build-caddy.ps1             # Script cross-compile Caddy via Go lokal
│   └── Caddyfile.example           # Contoh konfigurasi Caddy standalone
├── caddy-android-arm64             # Binary Caddy arm64 siap pakai
├── gradlew & gradlew.bat           # Gradle Wrapper
└── README.md                       # Panduan lengkap
```

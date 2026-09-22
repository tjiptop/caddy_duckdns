# Caddy Android & Windows HTTPS Reverse Proxy (DuckDNS DNS-01)

Solusi HTTPS Reverse Proxy otomatis di Android dan Windows untuk memungkinkan akses **HTML5 Camera API (`getUserMedia`)** dari perangkat tethering (client) melalui domain `https://tjiptop.duckdns.org:8443` (atau domain DuckDNS Anda) yang diteruskan ke aplikasi server lokal (misalnya PocketBase / Absenku di `127.0.0.1:8090`).

---

## 📁 Struktur Proyek (Model Absen_APK)

Proyek ini telah dibagi menjadi **3 varian mandiri** berdasarkan target versi OS Android dan arsitektur CPU:

```
x:\AIDEV\caddy\
│
├── caddy_bins\                        ← [SHARED] Sumber master biner Caddy
│   ├── caddy-linux-armv7              ← Biner 32-bit (ARMv7 / armeabi-v7a)
│   └── caddy-android-arm64            ← Biner 64-bit (ARM64 / arm64-v8a)
│
├── android5\                          ← Khusus Android 5.0 - 6.0 (Lollipop / Marshmallow)
│   ├── build.bat                      ← Build script otomatis (API 21 / arm5 / 32-bit)
│   ├── app\                           ← Proyek Gradle native (minSdk=21, abiFilters=['armeabi-v7a'])
│   └── gradlew.bat
│
├── android7\                          ← Khusus Android 7.0 - 9.0 (Nougat / Oreo / Pie)
│   ├── build.bat                      ← Build script otomatis (API 24 / arm7 / 32-bit)
│   ├── app\                           ← Proyek Gradle native (minSdk=24, abiFilters=['armeabi-v7a'])
│   └── gradlew.bat
│
├── android_modern\                    ← Khusus Android 10+ (Android 10 - 14+)
│   ├── build.bat                      ← Build script otomatis (API 29 / arm64 / 64-bit)
│   ├── app\                           ← Proyek Gradle native (minSdk=29, abiFilters=['arm64-v8a'])
│   └── gradlew.bat
│
├── apk_output\                        ← Output file APK yang siap dipasang
│   ├── CaddyProxy-v1.1-arm5.apk       ← (Android 5.0+, 32-bit armeabi-v7a)
│   ├── CaddyProxy-v1.1-arm7.apk       ← (Android 7.0+, 32-bit armeabi-v7a)
│   └── CaddyProxy-v1.1-arm64.apk      ← (Android 10+, 64-bit arm64-v8a)
│
├── build_all.bat                      ← Script 1-klik untuk build ketiga varian sekaligus
├── docs\                              ← Dokumentasi teknis lengkap (caddy.md)
└── windows\                           ← Aplikasi pendukung Caddy versi Windows
```

---

## 🚀 Cara Build APK

Anda dapat membangun APK per varian atau sekaligus semua varian:

### A. Build Semua Varian Sekaligus
Jalankan file di root:
```cmd
build_all.bat
```
Script akan secara berurutan mengompilasi ketiga varian dan meletakkannya di folder `apk_output\`.

### B. Build Per Varian

| Target Perangkat | Perintah / Script | Output APK |
| :--- | :--- | :--- |
| **Android 5.0 - 6.0** | `android5\build.bat` | `apk_output\CaddyProxy-v1.1-arm5.apk` |
| **Android 7.0 - 9.0** | `android7\build.bat` | `apk_output\CaddyProxy-v1.1-arm7.apk` |
| **Android 10+ (Modern)** | `android_modern\build.bat` | `apk_output\CaddyProxy-v1.1-arm64.apk` |

Setiap script `build.bat` secara otomatis:
1. Mendeteksi `ANDROID_HOME` (termasuk `X:\AIDEV\android-sdk`) dan `JAVA_HOME` (termasuk `X:\AIDEV\jdk-17`).
2. Menyinkronkan biner arsitektur yang sesuai dari folder `caddy_bins\` ke dalam `jniLibs/` dan `assets/`.
3. Membersihkan biner arsitektur silang sehingga APK Android 5 & 7 **murni 32-bit** dan Android Modern **murni 64-bit**.
4. Mengompilasi dengan Gradle dan menyalin APK final langsung ke folder pusat `apk_output\`.

---

## 🔒 Solusi Masalah SSL Cert pada Android 5

Jika pada Android 5 sebelumnya status sertifikat menampilkan **"Belum Ada"**, hal ini telah diatasi dengan perbaikan sistemik:

1. **Penyisipan Root CA ISRG Root X1 & X2**:
   - Android 5.0 (2014) tidak memiliki `ISRG Root X1` (root Let's Encrypt baru) di `/system/etc/security/cacerts`.
   - `CaddyService` kini selalu menyertakan `ISRG Root X1` dan `ISRG Root X2` secara terprogram ke dalam berkas `ca-certificates.crt` yang dibaca oleh Go TLS client Caddy (`SSL_CERT_FILE`).
2. **Koneksi DuckDNS Aman & Fallback HTTP**:
   - `DuckDnsHelper` dilengkapi `X509TrustManager` kustom dengan ISRG Root X1.
   - Jika HTTPS mengalami kendala pada Android lawas, request otomatis fallback ke HTTP (`http://www.duckdns.org/update...`) sehingga IP hotspot selalu berhasil diperbarui.
3. **Pencarian Rekursif Sertifikat ACME**:
   - Caddy menyimpan sertifikat di folder data (`caddy_data`). Service kini memindai direktori secara rekursif hingga menemukan file `.crt` dan `.key` domain terkait, lalu otomatis menyimpannya ke memori permanen (`SharedPreferences`).
4. **Indikator Status Pintar di UI**:
   - `Meminta SSL...` (Kuning) saat proses DNS-01 challenge sedang berlangsung.
   - `✅ Let's Encrypt` (Hijau) saat sertifikat resmi berhasil terpasang.
   - `Internal CA` (Biru muda) saat berjalan tanpa token dalam mode offline.

> **Catatan Penting Penggunaan Let's Encrypt**:
> Pastikan kolom **DuckDNS Token** diisi di aplikasi agar Caddy dapat menyelesaikan DNS-01 challenge dengan server Let's Encrypt. Jika token kosong, Caddy akan menggunakan sertifikat lokal internal.

---

## 📱 Cara Pemakaian di Android

1. Pasang file APK yang sesuai dari folder **`apk_output\`**:
   - Gunakan `CaddyProxy-v1.1-arm5.apk` untuk Android 5.
   - Gunakan `CaddyProxy-v1.1-arm7.apk` untuk Android 7 - 9.
   - Gunakan `CaddyProxy-v1.1-arm64.apk` untuk Android 10+.
2. Buka aplikasi **Caddy Reverse Proxy**.
3. Masukkan:
   - **DuckDNS Domain**: `tjiptop.duckdns.org`
   - **DuckDNS Token**: *(Token API DuckDNS Anda dari duckdns.org)*
   - **Target Host**: `127.0.0.1` (atau IP server aplikasi)
   - **Target Port**: `8090`
   - **HTTPS Listen Port**: `8443`
4. Tekan tombol **Mulai Caddy Proxy**.
5. Log akan menampilkan pembaruan DuckDNS, ekspor sertifikat root ISRG X1, dan inisialisasi Caddy HTTPS.

---

## 👥 Cara Perangkat Klien Membuka Web Absen

1. Hubungkan WiFi perangkat klien (HP karyawan/guru) ke Hotspot HP Server.
2. Buka browser di perangkat klien, ketik alamat portal:
   👉 **`http://IP_HOTSPOT:8080/`** (contoh: `http://192.168.2.230:8080/` atau klik tombol **Bagikan Link** di aplikasi server).
3. Tekan tombol **"⬇️ Unduh Sertifikat SSL (.crt)"** jika menggunakan browser yang membutuhkan impor CA root.
4. Buka **`https://tjiptop.duckdns.org:8443`** &rarr; Web langsung terbuka aman dengan kamera HTML5 aktif!

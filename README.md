# Caddy Android & Windows HTTPS Reverse Proxy (DuckDNS DNS-01)

Solusi HTTPS Reverse Proxy otomatis di Android dan Windows untuk memungkinkan akses **HTML5 Camera API (`getUserMedia`)** dari perangkat tethering (client) melalui domain `https://absenku.duckdns.org:8443` yang diteruskan ke aplikasi lokal di `127.0.0.1:8090`.

---

## 🎯 Fitur Utama

1. **100% Mandiri di Android (Tanpa Butuh PC)**:
   - Binary Caddy dikompilasi dengan **Android NDK Clang (CGO_ENABLED=1)** sehingga menggunakan resolver DNS bawaan Android (`getaddrinfo`).
   - Caddy di HP dapat menghubungi Let's Encrypt secara mandiri melalui koneksi internet HP, melakukan DuckDNS DNS-01 challenge, dan memasang sertifikat SSL resmi tanpa bantuan PC.
2. **Penyimpanan Permanen SSL (Anti-Hapus)**:
   - Begitu sertifikat diterbitkan, otomatis disimpan ke `SharedPreferences` dan direktori internal (`certs/`).
   - Sertifikat tidak akan hilang saat aplikasi di-update.
3. **Portal Download Sertifikat Klien (:8080)**:
   - Server HTTP ringan bawaan di port `8080` (contoh: `http://192.168.43.1:8080/`).
   - Klien lokal (karyawan/guru/siswa) yang terhubung ke hotspot dapat langsung mengunduh file `.crt` dengan 1-klik dan melihat panduan instalasi lengkap untuk Android, iOS, dan Windows.
4. **Fallback Offline Mandiri (`tls internal`)**:
   - Jika HP dinyalakan di area tanpa internet/kuota, Caddy otomatis beralih ke sertifikat internal mandiri sehingga server lokal tetap berjalan lancar.
5. **Dukungan Windows & Android Paritas**:
   - Versi Windows (`CaddyProxy.exe`) juga memiliki portal unduh klien di port `8080` dan tombol kirim sertifikat ke HP jika diinginkan.
6. **Ukuran APK Ramping (~23 MB)**:
   - Menggunakan Gradle ABI split (`arm64-v8a`, `armeabi-v7a`, dan `Universal`).

---

## 📱 Cara Pemakaian di HP Android (Sangat Mudah untuk Pemula)

### A. Pengaturan Pertama Kali (Di HP Server Baru)
1. Salin dan instal file **`CaddyProxy-Android-arm64-v8a.apk`** (atau `CaddyProxy-Android.apk`) di HP.
2. Buka aplikasi **Caddy Reverse Proxy**.
3. Masukkan:
   - **DuckDNS Domain**: `absenku.duckdns.org`
   - **DuckDNS Token**: *(Token API DuckDNS Anda)*
   - **Target Host**: `127.0.0.1`
   - **Target Port**: `8090`
   - **HTTPS Listen Port**: `8443`
4. Tekan tombol **Start Caddy Proxy**.
5. **Selesai!** 
   - Aplikasi otomatis mengarahkan IP DuckDNS ke IP Hotspot HP.
   - Caddy langsung mengambil sertifikat resmi Let's Encrypt melalui internet HP.
   - Layanan langsung aktif di `https://absenku.duckdns.org:8443`.

---

## 👥 Cara Perangkat Klien (Karyawan / Guru) Membuka Web Absen

Saat HP server mengaktifkan hotspot/tethering:
1. Hubungkan WiFi perangkat klien ke Hotspot HP Server.
2. Buka browser di perangkat klien, ketik alamat portal:
   👉 **`http://IP_HOTSPOT:8080/`** (misal: `http://192.168.43.1:8080/` atau bagikan link dari tombol **Bagikan Link** di aplikasi HP server).
3. Tekan tombol **"⬇️ Unduh Sertifikat SSL (.crt)"** lalu ikuti panduan singkat instalasi di layar.
4. Buka **`https://absenku.duckdns.org:8443`** &rarr; Web langsung terbuka dengan aman dan kamera HTML5 langsung aktif!

---

## 💻 Cara Menjalankan di Windows

1. Jalankan `windows\Fix-Firewall.bat` sebagai Administrator (hanya sekali untuk membuka port 8080 dan 8443).
2. Jalankan `windows\CaddyProxy.exe`.
3. Masukkan domain DuckDNS dan Token, lalu klik **Mulai Caddy Proxy**.
4. Tombol **"🌐 Buka Portal Unduh Klien"** akan membuka browser di `http://localhost:8080`.
5. Tombol **"📲 Kirim Sertifikat ke HP"** dapat mengirimkan sertifikat yang ada di PC langsung ke server HP Android jika diperlukan.

---

## 📦 File APK yang Tersedia

Di folder root project atau tab [Releases di GitHub](https://github.com/tjiptop/caddy_duckdns/releases):
- `CaddyProxy-Android-arm64-v8a.apk` (~23.2 MB) &rarr; Untuk sebagian besar HP Android modern (64-bit).
- `CaddyProxy-Android-armeabi-v7a.apk` (~24.0 MB) &rarr; Untuk HP Android lama (32-bit).
- `CaddyProxy-Android-Universal.apk` (~41.0 MB) &rarr; Berisi semua arsitektur.

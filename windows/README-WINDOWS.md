# Caddy HTTPS Reverse Proxy untuk Windows

Aplikasi desktop Windows native (GUI Modern Dark Theme) yang memiliki fitur dan tampilan identik dengan versi Android:
* Menggunakan binary Caddy Windows 64-bit resmi (`caddy.exe`) yang sudah terintegrasi dengan modul `caddy-dns/duckdns`.
* Textbox konfigurasi lengkap dengan auto-save ke `caddy_proxy_config.json`.
* Otomatis mengupdate DNS DuckDNS saat Start.
* Monitor log Caddy secara real-time.
* Dapat berjalan di background / System Tray.

---

## 🛠️ Instalasi & Optimalisasi Server 1-Click

Agar server berjalan dengan performa maksimal dan tidak diblokir oleh sistem keamanan Windows:

1. Buka folder `D:\AIDEV\caddy\windows\`
2. Klik kanan file **`Setup.bat`** lalu pilih **"Run as administrator"** (atau cukup double-click `Setup.bat` dan pilih **Yes** pada prompt UAC).
3. Installer akan otomatis:
   * **Membuka Windows Firewall**: Mengizinkan program `caddy.exe`, port HTTPS `8443`, port `8090`, dan port `443` untuk koneksi masuk (Inbound).
   * **Mengoptimalkan Real-time Antivirus**: Menambahkan pengecualian (Exclusion) pada folder instalasi dan proses `caddy.exe` / `CaddyProxy.exe` di Windows Defender, sehingga scanning real-time tidak membebani CPU atau memperlambat lalu lintas streaming kamera/data.
   * **Memasang Shortcut**: Membuat shortcut resmi di Desktop dan Start Menu Anda.

Untuk menghapus seluruh aturan Firewall dan pengecualian jika tidak lagi digunakan, Anda cukup menjalankan file **`Uninstall.bat`** sebagai Administrator.

---

## 🚀 Cara Menjalankan Aplikasi


## 🛠️ File di Folder `windows/`

* `CaddyProxy.exe` — Aplikasi Desktop GUI utama.
* `caddy.exe` — Binary Caddy Windows 64-bit + DuckDNS module.
* `CaddyProxyApp.cs` — Source code C# Windows Forms.
* `Install-Shortcut.bat` — Membuat shortcut di Desktop Anda.
* `build-windows.bat` — Meng-compile ulang `CaddyProxy.exe` menggunakan C# compiler bawaan Windows (`csc.exe`).

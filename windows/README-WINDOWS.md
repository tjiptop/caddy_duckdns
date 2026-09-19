# Caddy HTTPS Reverse Proxy untuk Windows

Aplikasi desktop Windows native (GUI Modern Dark Theme) yang memiliki fitur dan tampilan identik dengan versi Android:
* Menggunakan binary Caddy Windows 64-bit resmi (`caddy.exe`) yang sudah terintegrasi dengan modul `caddy-dns/duckdns`.
* Textbox konfigurasi lengkap dengan auto-save ke `caddy_proxy_config.json`.
* Otomatis mengupdate DNS DuckDNS saat Start.
* Monitor log Caddy secara real-time.
* Dapat berjalan di background / System Tray.

---

## 🚀 Cara Menjalankan

1. Cukup double-click **shortcut di Desktop**: **`Caddy HTTPS Proxy`**  
   *(Atau buka langsung file `D:\AIDEV\caddy\windows\CaddyProxy.exe`)*
2. Masukkan parameter:
   * **DuckDNS Domain**: `tjipto.duckdns.org`
   * **DuckDNS Token**: Token API Anda dari duckdns.org
   * **Target Host & Port**: `127.0.0.1` dan `8090` (Aplikasi lokal yang akan di-forward)
   * **HTTPS Port**: `8443` (atau port lain)
   * **Custom IP**: *(Opsional)* Jika dikosongkan, otomatis mendeteksi IP adapter aktif
3. Klik **Simpan Pengaturan** untuk menyimpan setting.
4. Klik **Start Caddy Proxy**.
5. Buka browser di perangkat apa pun di jaringan yang sama:
   ```
   https://tjipto.duckdns.org:8443
   ```

---

## 🛠️ File di Folder `windows/`

* `CaddyProxy.exe` — Aplikasi Desktop GUI utama.
* `caddy.exe` — Binary Caddy Windows 64-bit + DuckDNS module.
* `CaddyProxyApp.cs` — Source code C# Windows Forms.
* `Install-Shortcut.bat` — Membuat shortcut di Desktop Anda.
* `build-windows.bat` — Meng-compile ulang `CaddyProxy.exe` menggunakan C# compiler bawaan Windows (`csc.exe`).

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

## 🔄 Menjadikan Windows Service (Auto-Start Saat Booting)

Jika Anda ingin Caddy **langsung aktif otomatis setiap kali komputer/laptop dinyalakan tanpa perlu login atau membuka aplikasi secara manual**:

### Cara Pasang Service:
1. Klik kanan file **`Install-Service.bat`** lalu pilih **"Run as administrator"** (atau klik tombol **"⚙️ Pasang Auto-Start (Windows Service)"** di dalam aplikasi `CaddyProxy.exe`).
2. Service akan otomatis terdaftar di Windows Task Scheduler dengan konfigurasi:
   * **Trigger**: `ONSTART` (Berjalan saat Windows boot sebelum user login).
   * **User**: `SYSTEM` (Privilege tertinggi).
   * **Action**: Menjalankan `service-runner.ps1` yang otomatis menunggu koneksi jaringan, memperbarui DNS DuckDNS, dan menjalankan Caddy secara silent di background.
   * **Log runtime**: Tercatat di `service.log`.

### Cara Menonaktifkan Service:
* Klik kanan file **`Uninstall-Service.bat`** lalu pilih **"Run as administrator"**. Service akan dihentikan dan dihapus dari autostart.

---

## 🚀 Cara Menjalankan Aplikasi Secara Manual

1. Buka shortcut di Desktop: **`Caddy HTTPS Proxy`**  
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

## 📁 Daftar File di Folder `windows/`

* `CaddyProxy.exe` — Aplikasi Desktop GUI utama.
* `caddy.exe` — Binary Caddy Windows 64-bit + DuckDNS module.
* `Setup.bat` — Installer 1-click untuk Firewall & pengecualian Antivirus Defender.
* `Uninstall.bat` — Pembersih aturan Firewall & Antivirus.
* `Install-Service.bat` — Mendaftarkan Caddy sebagai background service autostart saat booting.
* `Uninstall-Service.bat` — Menonaktifkan background service autostart.
* `service-runner.ps1` — Skrip background service runner dengan auto-retry jaringan & auto-update DuckDNS.
* `CaddyProxyApp.cs` — Source code C# Windows Forms.
* `build-windows.bat` — Skrip re-compile `CaddyProxy.exe`.

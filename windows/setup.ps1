<#
.SYNOPSIS
    Instalasi dan Optimalisasi Server Caddy Proxy untuk Windows.
    - Menambahkan Pengecualian Windows Defender (Real-time Scanner) agar performa I/O & jaringan maksimal.
    - Membuka Port dan mengizinkan caddy.exe pada Windows Defender Firewall.
    - Membuat Shortcut di Desktop dan Start Menu.
#>

$ErrorActionPreference = "Continue"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "   SETUP & OPTIMALISASI SERVER CADDY HTTPS PROXY (WINDOWS)      " -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$installDir = $PSScriptRoot
if (-not $installDir) {
    $installDir = (Get-Item .).FullName
}

Write-Host "[1/4] Lokasi Instalasi..." -ForegroundColor Yellow
Write-Host "      Folder: $installDir" -ForegroundColor Gray

# -------------------------------------------------------------
# 1. OPTIMALISASI REAL-TIME SCANNER (WINDOWS DEFENDER)
# -------------------------------------------------------------
Write-Host ""
Write-Host "[2/4] Mengonfigurasi Pengecualian Windows Defender Antivirus..." -ForegroundColor Yellow
try {
    # Folder exclusion
    Add-MpPreference -ExclusionPath $installDir -ErrorAction Stop
    Write-Host "      [OK] Folder dikecualikan dari scanning: $installDir" -ForegroundColor Green

    # Process exclusions
    Add-MpPreference -ExclusionProcess "caddy.exe" -ErrorAction Stop
    Add-MpPreference -ExclusionProcess "CaddyProxy.exe" -ErrorAction Stop
    Write-Host "      [OK] Proses 'caddy.exe' dikecualikan dari Real-time Scanner" -ForegroundColor Green
    Write-Host "      [OK] Proses 'CaddyProxy.exe' dikecualikan dari Real-time Scanner" -ForegroundColor Green
}
catch {
    Write-Host "      [CATATAN] Defender Antivirus: $($_.Exception.Message)" -ForegroundColor DarkYellow
    Write-Host "      (Pastikan Anda menjalankan skrip ini melalui Setup.bat sebagai Administrator)" -ForegroundColor Gray
}

# -------------------------------------------------------------
# 2. KONFIGURASI WINDOWS DEFENDER FIREWALL
# -------------------------------------------------------------
Write-Host ""
Write-Host "[3/4] Mengonfigurasi Aturan Windows Firewall (Port & Program)..." -ForegroundColor Yellow
$firewallSuccess = $true
try {
    $caddyExePath = Join-Path $installDir "caddy.exe"

    # Hapus rule lama jika ada agar tidak duplikat
    Remove-NetFirewallRule -DisplayName "Caddy Server Program" -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -DisplayName "Caddy HTTPS Port 8443" -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -DisplayName "Caddy Backend Port 8090" -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -DisplayName "Caddy HTTPS Port 443" -ErrorAction SilentlyContinue

    # Izinkan caddy.exe program secara menyeluruh
    if (Test-Path $caddyExePath) {
        New-NetFirewallRule -DisplayName "Caddy Server Program" `
            -Direction Inbound `
            -Program $caddyExePath `
            -Action Allow `
            -Profile Any `
            -Description "Izinkan seluruh traffic masuk untuk Caddy Web Server" `
            -ErrorAction Stop | Out-Null
        Write-Host "      [OK] Firewall Rule: Program caddy.exe diizinkan penuh (Inbound)" -ForegroundColor Green
    }

    # Izinkan Port 8443 (HTTPS default Caddy)
    New-NetFirewallRule -DisplayName "Caddy HTTPS Port 8443" `
        -Direction Inbound `
        -LocalPort 8443 `
        -Protocol TCP `
        -Action Allow `
        -Profile Any `
        -Description "Port HTTPS Reverse Proxy Caddy" `
        -ErrorAction Stop | Out-Null
    Write-Host "      [OK] Firewall Rule: Port 8443 TCP dibuka (Inbound)" -ForegroundColor Green

    # Izinkan Port 8090 (Backend target)
    New-NetFirewallRule -DisplayName "Caddy Backend Port 8090" `
        -Direction Inbound `
        -LocalPort 8090 `
        -Protocol TCP `
        -Action Allow `
        -Profile Any `
        -Description "Port Backend Target Lokal" `
        -ErrorAction Stop | Out-Null
    Write-Host "      [OK] Firewall Rule: Port 8090 TCP dibuka (Inbound)" -ForegroundColor Green

    # Izinkan Port 443 (Standar HTTPS jika digunakan)
    New-NetFirewallRule -DisplayName "Caddy HTTPS Port 443" `
        -Direction Inbound `
        -LocalPort 443 `
        -Protocol TCP `
        -Action Allow `
        -Profile Any `
        -Description "Port 443 Standar HTTPS Caddy" `
        -ErrorAction Stop | Out-Null
    Write-Host "      [OK] Firewall Rule: Port 443 TCP dibuka (Inbound)" -ForegroundColor Green
}
catch {
    $firewallSuccess = $false
    Write-Host "      [PERINGATAN] Gagal mengatur Windows Firewall: Akses Ditolak (Perlu hak Administrator)" -ForegroundColor Red
    Write-Host "      Solusi: Double-click file 'Setup.bat' untuk menjalankan dengan hak Administrator." -ForegroundColor Yellow
}

# -------------------------------------------------------------
# 3. MEMBUAT SHORTCUT DESKTOP & START MENU
# -------------------------------------------------------------
Write-Host ""
Write-Host "[4/4] Membuat Shortcut Desktop & Start Menu..." -ForegroundColor Yellow
try {
    $ws = New-Object -ComObject WScript.Shell
    $targetExe = Join-Path $installDir "CaddyProxy.exe"

    # Desktop Shortcut
    $desktopPath = [Environment]::GetFolderPath('Desktop')
    $deskShortcut = $ws.CreateShortcut((Join-Path $desktopPath "Caddy HTTPS Proxy.lnk"))
    $deskShortcut.TargetPath = $targetExe
    $deskShortcut.WorkingDirectory = $installDir
    $deskShortcut.IconLocation = "$targetExe,0"
    $deskShortcut.Description = "Caddy HTTPS Reverse Proxy (DuckDNS)"
    $deskShortcut.Save()
    Write-Host "      [OK] Shortcut Desktop: 'Caddy HTTPS Proxy'" -ForegroundColor Green

    # Start Menu Shortcut
    $programsPath = [Environment]::GetFolderPath('Programs')
    $startShortcut = $ws.CreateShortcut((Join-Path $programsPath "Caddy HTTPS Proxy.lnk"))
    $startShortcut.TargetPath = $targetExe
    $startShortcut.WorkingDirectory = $installDir
    $startShortcut.IconLocation = "$targetExe,0"
    $startShortcut.Description = "Caddy HTTPS Reverse Proxy (DuckDNS)"
    $startShortcut.Save()
    Write-Host "      [OK] Shortcut Start Menu: 'Caddy HTTPS Proxy'" -ForegroundColor Green
}
catch {
    Write-Host "      [WARNING] Gagal membuat shortcut: $($_.Exception.Message)" -ForegroundColor DarkYellow
}

Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
if ($firewallSuccess) {
    Write-Host "   INSTALASI & OPTIMALISASI SERVER SELESAI DENGAN SUKSES!       " -ForegroundColor Green
    Write-Host "================================================================" -ForegroundColor Cyan
    Write-Host "Firewall telah dibuka dan Antivirus Scanner tidak akan memperlambat" -ForegroundColor Gray
    Write-Host "kinerja server Caddy. Anda dapat langsung membuka CaddyProxy.exe." -ForegroundColor Gray
} else {
    Write-Host "   PENGECUALIAN ANTIVIRUS & SHORTCUT SELESAI                    " -ForegroundColor Yellow
    Write-Host "================================================================" -ForegroundColor Cyan
    Write-Host "Untuk membuka port Firewall, silakan jalankan file 'Setup.bat'  " -ForegroundColor Yellow
    Write-Host "(klik kanan -> Run as administrator jika prompt tidak muncul).  " -ForegroundColor Yellow
}
Write-Host ""

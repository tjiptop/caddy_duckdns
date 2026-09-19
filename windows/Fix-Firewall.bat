@echo off
setlocal
cd /d "%~dp0"

echo ================================================================
echo   MEMPERBAIKI ATURAN WINDOWS DEFENDER FIREWALL UNTUK CADDY
echo ================================================================
echo.

:: Meminta hak akses Administrator
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [INFO] Memerlukan hak akses Administrator...
    echo Membuka prompt konfirmasi UAC Windows...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \"\"%~f0\"\"' -Verb RunAs"
    exit /b
)

echo [1/3] Memperbarui profil aturan Caddy ke 'Any' (Private, Public, Domain)...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Set-NetFirewallRule -DisplayName 'Caddy' -Profile Any -ErrorAction SilentlyContinue"

echo [2/3] Membuka port 80, 443, 8443, dan 8090 untuk seluruh jaringan...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Remove-NetFirewallRule -DisplayName 'Caddy Server*' -ErrorAction SilentlyContinue"
powershell -NoProfile -ExecutionPolicy Bypass -Command "New-NetFirewallRule -DisplayName 'Caddy Server Program' -Direction Inbound -Program '%~dp0caddy.exe' -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null"
powershell -NoProfile -ExecutionPolicy Bypass -Command "New-NetFirewallRule -DisplayName 'Caddy Server Port 443' -Direction Inbound -LocalPort 443 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null"
powershell -NoProfile -ExecutionPolicy Bypass -Command "New-NetFirewallRule -DisplayName 'Caddy Server Port 80' -Direction Inbound -LocalPort 80 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null"
powershell -NoProfile -ExecutionPolicy Bypass -Command "New-NetFirewallRule -DisplayName 'Caddy Server Port 8443' -Direction Inbound -LocalPort 8443 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null"
powershell -NoProfile -ExecutionPolicy Bypass -Command "New-NetFirewallRule -DisplayName 'Caddy Server Port 8090' -Direction Inbound -LocalPort 8090 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null"
powershell -NoProfile -ExecutionPolicy Bypass -Command "New-NetFirewallRule -DisplayName 'Caddy Server Port 8080' -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow -Profile Any -ErrorAction SilentlyContinue | Out-Null"

echo [3/3] Menambahkan pengecualian Windows Defender...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Add-MpPreference -ExclusionPath '%~dp0' -ErrorAction SilentlyContinue"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Add-MpPreference -ExclusionProcess 'caddy.exe' -ErrorAction SilentlyContinue"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Add-MpPreference -ExclusionProcess 'CaddyProxy.exe' -ErrorAction SilentlyContinue"

echo.
echo ================================================================
echo   [BERHASIL] Port 80, 443, 8443 dan Firewall Caddy Telah Dibuka!
echo   Sekarang HP / Client di jaringan Wi-Fi sudah bisa mengakses!
echo ================================================================
echo.
timeout /t 5

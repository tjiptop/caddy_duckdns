@echo off
setlocal
cd /d "%~dp0"

echo ================================================================
echo   UNINSTALL CADDY WINDOWS SYSTEM SERVICE
echo ================================================================
echo.

:: Check for Administrator privileges
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [INFO] Memerlukan hak akses Administrator untuk menghapus Service...
    echo Membuka prompt konfirmasi UAC...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \"\"%~f0\"\"' -Verb RunAs"
    exit /b
)

set TASK_NAME=CaddyHttpsServerService

echo [1/2] Menghentikan proses Caddy...
taskkill /F /IM caddy.exe >nul 2>&1
echo       [OK] Proses Caddy dihentikan.

echo [2/2] Menghapus Service '%TASK_NAME%' dari Task Scheduler...
schtasks /delete /tn "%TASK_NAME%" /f >nul 2>&1
if %errorlevel% equ 0 (
    echo       [OK] Service '%TASK_NAME%' berhasil dihapus.
) else (
    echo       [INFO] Service tidak ditemukan di Task Scheduler.
)

echo.
echo ================================================================
echo   SERVICE CADDY BERHASIL DINONAKTIFKAN!
echo ================================================================
echo Caddy tidak akan lagi berjalan otomatis saat Windows booting.
echo Anda tetap dapat menjalankannya secara manual melalui CaddyProxy.exe.
echo.

timeout /t 5

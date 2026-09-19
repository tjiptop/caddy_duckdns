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

if exist "%~dp0nssm.exe" (
    echo [1/2] Menghentikan dan menghapus Service NSSM CaddyProxy...
    "%~dp0nssm.exe" stop CaddyProxy >nul 2>&1
    "%~dp0nssm.exe" remove CaddyProxy confirm >nul 2>&1
    echo       [OK] Service NSSM berhasil dihapus.
)

set TASK_NAME=CaddyHttpsServerService

echo [2/2] Menghentikan proses Caddy dan menghapus Task Scheduler jika ada...
taskkill /F /IM caddy.exe >nul 2>&1
schtasks /delete /tn "%TASK_NAME%" /f >nul 2>&1

echo.
echo ================================================================
echo   SERVICE CADDY BERHASIL DINONAKTIFKAN!
echo ================================================================
echo Caddy tidak akan lagi berjalan otomatis saat Windows booting.
echo Anda tetap dapat menjalankannya secara manual melalui CaddyProxy.exe.
echo.

timeout /t 4

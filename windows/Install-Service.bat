@echo off
setlocal
cd /d "%~dp0"

echo ================================================================
echo   INSTALL CADDY PROXY AS WINDOWS SYSTEM SERVICE (AUTOSTART)
echo ================================================================
echo.

:: Check for Administrator privileges
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [INFO] Memerlukan hak akses Administrator untuk mendaftarkan Service...
    echo Membuka prompt konfirmasi UAC...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \"\"%~f0\"\"' -Verb RunAs"
    exit /b
)

if exist "%~dp0nssm.exe" (
    echo [1/3] Menggunakan NSSM (Non-Sucking Service Manager)...
    "%~dp0nssm.exe" stop CaddyProxy >nul 2>&1
    "%~dp0nssm.exe" remove CaddyProxy confirm >nul 2>&1
    
    echo [2/3] Mendaftarkan Service CaddyProxy...
    "%~dp0nssm.exe" install CaddyProxy "%~dp0CaddyProxy.exe" -service
    "%~dp0nssm.exe" set CaddyProxy AppDirectory "%~dp0"
    "%~dp0nssm.exe" set CaddyProxy DisplayName "Caddy DuckDNS HTTPS Proxy"
    "%~dp0nssm.exe" set CaddyProxy Description "Reverse proxy HTTPS otomatis untuk DuckDNS menggunakan Caddy Server"
    "%~dp0nssm.exe" set CaddyProxy Start SERVICE_AUTO_START
    "%~dp0nssm.exe" set CaddyProxy AppStdout "%~dp0service.log"
    "%~dp0nssm.exe" set CaddyProxy AppStderr "%~dp0service_error.log"
    "%~dp0nssm.exe" set CaddyProxy AppRotateFiles 1
    "%~dp0nssm.exe" set CaddyProxy AppRotateOnline 1
    "%~dp0nssm.exe" set CaddyProxy AppRotateBytes 10485760

    echo [3/3] Menjalankan Service CaddyProxy...
    "%~dp0nssm.exe" start CaddyProxy
    echo.
    echo ================================================================
    echo   SUKSES: CADDY KINI BERJALAN SEBAGAI WINDOWS SERVICE (NSSM)!
    echo ================================================================
    echo - Caddy akan otomatis aktif saat Windows booting via NSSM Service.
    echo - Log runtime tersimpan di: %~dp0service.log
    echo - Untuk menonaktifkan, jalankan: Uninstall-Service.bat
    echo.
    timeout /t 5
    exit /b 0
)

set TASK_NAME=CaddyHttpsServerService
set RUNNER_SCRIPT=%~dp0service-runner.ps1

echo [1/3] Memeriksa file service-runner.ps1...
if not exist "%RUNNER_SCRIPT%" (
    echo [ERROR] File %RUNNER_SCRIPT% tidak ditemukan!
    pause
    exit /b 1
)
echo       [OK] File runner siap.

echo [2/3] Mendaftarkan Task Scheduler (Autostart on Boot: ONSTART)...
schtasks /create /tn "%TASK_NAME%" /tr "powershell.exe -WindowStyle Hidden -ExecutionPolicy Bypass -File \"%RUNNER_SCRIPT%\"" /sc ONSTART /ru SYSTEM /rl HIGHEST /f >nul 2>&1

if %errorlevel% equ 0 (
    echo       [OK] Service '%TASK_NAME%' berhasil didaftarkan!
) else (
    echo       [WARNING] Gagal mendaftarkan sebagai SYSTEM, mencoba mode ONLOGON...
    schtasks /create /tn "%TASK_NAME%" /tr "powershell.exe -WindowStyle Hidden -ExecutionPolicy Bypass -File \"%RUNNER_SCRIPT%\"" /sc ONLOGON /rl HIGHEST /f >nul 2>&1
    if %errorlevel% equ 0 (
        echo       [OK] Service '%TASK_NAME%' berhasil didaftarkan (Mode Logon)!
    ) else (
        echo       [ERROR] Gagal mendaftarkan Service di Task Scheduler.
        pause
        exit /b 1
    )
)

echo [3/3] Menjalankan Service sekarang...
schtasks /run /tn "%TASK_NAME%" >nul 2>&1
echo       [OK] Service telah dijalankan di background.

echo.
echo ================================================================
echo   SUKSES: CADDY KINI BERJALAN OTOMATIS SAAT WINDOWS BOOTING!
echo ================================================================
echo - Caddy akan otomatis aktif setiap kali komputer/laptop dinyalakan
echo   tanpa perlu membuka aplikasi atau mengklik Start secara manual.
echo - File log runtime dapat dilihat di: %~dp0service.log
echo - Untuk menonaktifkan, jalankan file: Uninstall-Service.bat
echo.

timeout /t 5

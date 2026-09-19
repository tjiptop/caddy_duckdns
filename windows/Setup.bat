@echo off
setlocal
cd /d "%~dp0"

echo ================================================================
echo   CADDY HTTPS PROXY - SERVER INSTALLER & OPTIMIZER
echo ================================================================
echo.

:: Check for Administrator privileges
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [INFO] Memerlukan hak akses Administrator untuk mengatur Firewall dan Antivirus...
    echo Membuka prompt konfirmasi UAC...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd.exe -ArgumentList '/c \"\"%~f0\"\"' -Verb RunAs"
    exit /b
)

:: Run setup.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"

echo.
echo Tekan sembarang tombol untuk keluar...
pause >nul

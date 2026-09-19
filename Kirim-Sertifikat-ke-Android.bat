@echo off
setlocal enabledelayedexpansion
title Kirim Sertifikat SSL Caddy ke HP Android
color 0B

echo ====================================================
echo     KIRIM SERTIFIKAT SSL CADDY PC KE ANDROID
echo ====================================================
echo.

set DEFAULT_IP=192.168.2.242
set /p TARGET_IP="Masukkan IP HP Android [%DEFAULT_IP%]: "
if "%TARGET_IP%"=="" set TARGET_IP=%DEFAULT_IP%

echo.
echo Mengirim sertifikat ke http://%TARGET_IP%:8080/ ...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-cert.ps1" -PhoneIp "%TARGET_IP%"

echo.
echo ====================================================
pause

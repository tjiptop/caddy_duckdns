@echo off
setlocal
cd /d "%~dp0"

echo ================================================================
echo   MEMBANGUN INSTALLER CADDY HTTPS PROXY (INNO SETUP)
echo ================================================================
echo.

set "ISCC_PATH="
if exist "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" (
    set "ISCC_PATH=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
) else if exist "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" (
    set "ISCC_PATH=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
) else if exist "%ProgramFiles%\Inno Setup 6\ISCC.exe" (
    set "ISCC_PATH=%ProgramFiles%\Inno Setup 6\ISCC.exe"
)

if "%ISCC_PATH%"=="" (
    echo [ERROR] Inno Setup 6 Compiler (ISCC.exe) tidak ditemukan!
    echo Pastikan Inno Setup 6 terinstall di komputer Anda.
    pause
    exit /b 1
)

echo Menggunakan compiler: "%ISCC_PATH%"
echo Mengompilasi CaddyProxySetup.iss...
echo.

"%ISCC_PATH%" "CaddyProxySetup.iss"

if %errorlevel% equ 0 (
    echo.
    echo ================================================================
    echo   [BERHASIL] Installer siap di folder: Output\
    echo   File: Output\CaddyProxy-Setup-Windows.exe
    echo ================================================================
) else (
    echo.
    echo [GAGAL] Terjadi kesalahan saat mengompilasi installer.
)

echo.
pause

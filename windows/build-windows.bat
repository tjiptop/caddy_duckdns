@echo off
setlocal
cd /d "%~dp0"

echo Compiling CaddyProxy.exe...
"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /target:winexe /out:CaddyProxy.exe /r:System.Net.Http.dll CaddyProxyApp.cs

if %errorlevel% equ 0 (
    echo [OK] Compilation successful: CaddyProxy.exe
) else (
    echo [ERROR] Compilation failed.
)
timeout /t 3

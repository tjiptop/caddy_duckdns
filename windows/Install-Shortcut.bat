@echo off
setlocal
cd /d "%~dp0"

echo ======================================================
echo   Installing Caddy HTTPS Proxy Shortcut on Desktop...
echo ======================================================

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut([System.IO.Path]::Combine([Environment]::GetFolderPath('Desktop'), 'Caddy HTTPS Proxy.lnk')); $s.TargetPath = '%~dp0CaddyProxy.exe'; $s.WorkingDirectory = '%~dp0'; $s.IconLocation = '%~dp0CaddyProxy.exe,0'; $s.Description = 'Caddy HTTPS Reverse Proxy (DuckDNS)'; $s.Save()"

if %errorlevel% equ 0 (
    echo [OK] Shortcut created on your Desktop!
    echo You can now double-click 'Caddy HTTPS Proxy' on your Desktop.
) else (
    echo [ERROR] Failed to create desktop shortcut.
)
timeout /t 5

<#
.SYNOPSIS
    Pembersihan / Uninstalasi Server Caddy Proxy untuk Windows.
    - Menghapus aturan Firewall untuk Caddy.
    - Menghapus pengecualian Windows Defender.
    - Menghapus shortcut Desktop dan Start Menu.
#>

$ErrorActionPreference = "Continue"

Write-Host "================================================================" -ForegroundColor Yellow
Write-Host "   UNINSTALL & PEMBERSIHAN SERVER CADDY HTTPS PROXY (WINDOWS)   " -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host ""

$installDir = $PSScriptRoot
if (-not $installDir) {
    $installDir = (Get-Item .).FullName
}

# 1. HAPUS FIREWALL RULES
Write-Host "[1/3] Menghapus aturan Windows Defender Firewall..." -ForegroundColor Cyan
Remove-NetFirewallRule -DisplayName "Caddy Server Program" -ErrorAction SilentlyContinue
Remove-NetFirewallRule -DisplayName "Caddy HTTPS Port 8443" -ErrorAction SilentlyContinue
Remove-NetFirewallRule -DisplayName "Caddy Backend Port 8090" -ErrorAction SilentlyContinue
Remove-NetFirewallRule -DisplayName "Caddy HTTPS Port 443" -ErrorAction SilentlyContinue
Write-Host "      [OK] Semua aturan Firewall Caddy telah dihapus." -ForegroundColor Green

# 2. HAPUS DEFENDER EXCLUSIONS
Write-Host "[2/3] Menghapus pengecualian Windows Defender Antivirus..." -ForegroundColor Cyan
Remove-MpPreference -ExclusionPath $installDir -ErrorAction SilentlyContinue
Remove-MpPreference -ExclusionProcess "caddy.exe" -ErrorAction SilentlyContinue
Remove-MpPreference -ExclusionProcess "CaddyProxy.exe" -ErrorAction SilentlyContinue
Write-Host "      [OK] Pengecualian Antivirus telah dihapus." -ForegroundColor Green

# 3. HAPUS SHORTCUT
Write-Host "[3/3] Menghapus shortcut..." -ForegroundColor Cyan
$desktopShortcut = Join-Path ([Environment]::GetFolderPath('Desktop')) "Caddy HTTPS Proxy.lnk"
$startShortcut = Join-Path ([Environment]::GetFolderPath('Programs')) "Caddy HTTPS Proxy.lnk"

if (Test-Path $desktopShortcut) { Remove-Item $desktopShortcut -Force }
if (Test-Path $startShortcut) { Remove-Item $startShortcut -Force }
Write-Host "      [OK] Shortcut Desktop dan Start Menu telah dihapus." -ForegroundColor Green

Write-Host ""
Write-Host "Pembersihan selesai!" -ForegroundColor Green
Write-Host ""

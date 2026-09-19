param(
    [string]$PhoneIp = "192.168.2.242",
    [int]$Port = 8080,
    [string]$Domain = "absenku.duckdns.org"
)

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "   PENGIRIM SERTIFIKAT SSL KE CADDY PROXY ANDROID   " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

$certDir = "$env:APPDATA\Caddy\certificates\acme-v02.api.letsencrypt.org-directory\$Domain"
$crtFile = "$certDir\$Domain.crt"
$keyFile = "$certDir\$Domain.key"

if (-not (Test-Path $crtFile) -or -not (Test-Path $keyFile)) {
    # Search recursively if path is different
    $foundCrt = Get-ChildItem -Path "$env:APPDATA\Caddy\certificates" -Filter "$Domain.crt" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    $foundKey = Get-ChildItem -Path "$env:APPDATA\Caddy\certificates" -Filter "$Domain.key" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($foundCrt -and $foundKey) {
        $crtFile = $foundCrt.FullName
        $keyFile = $foundKey.FullName
    } else {
        Write-Host "ERROR: File sertifikat $Domain.crt / .key tidak ditemukan di Caddy PC!" -ForegroundColor Red
        Write-Host "Lokasi pencarian: $env:APPDATA\Caddy\certificates\" -ForegroundColor Yellow
        exit 1
    }
}

Write-Host "File CRT : $crtFile ($( (Get-Item $crtFile).Length ) bytes)" -ForegroundColor Green
Write-Host "File KEY : $keyFile ($( (Get-Item $keyFile).Length ) bytes)" -ForegroundColor Green
Write-Host "Target HP: http://$PhoneIp`:$Port/upload-cert" -ForegroundColor Yellow

$crtContent = Get-Content $crtFile -Raw
$keyContent = Get-Content $keyFile -Raw

$payload = @{
    domain = $Domain
    crt = $crtContent
    key = $keyContent
} | ConvertTo-Json

$url = "http://$PhoneIp`:$Port/upload-cert"

try {
    Write-Host "`nSedang mengirim sertifikat ke Android..." -ForegroundColor Cyan
    $response = Invoke-RestMethod -Uri $url -Method Post -Body $payload -ContentType "application/json; charset=utf-8" -TimeoutSec 10
    Write-Host "`nSUKSES: Sertifikat berhasil diterima oleh Android!" -ForegroundColor Green
    Write-Host "Respon: $( $response | ConvertTo-Json -Compress )" -ForegroundColor White
    Write-Host "`nSertifikat kini telah disimpan secara permanen di SharedPreferences HP." -ForegroundColor Green
} catch {
    Write-Host "`nGAGAL mengirim sertifikat ke $url" -ForegroundColor Red
    Write-Host "Penyebab: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Pastikan:" -ForegroundColor Yellow
    Write-Host "1. HP dan Laptop berada dalam 1 jaringan Wi-Fi/Hotspot yang sama." -ForegroundColor Yellow
    Write-Host "2. Aplikasi Caddy di HP sedang dibuka (Portal Port 8080 aktif)." -ForegroundColor Yellow
    Write-Host "3. Alamat IP HP ($PhoneIp) sudah benar." -ForegroundColor Yellow
}

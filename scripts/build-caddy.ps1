# Script untuk cross-compile Caddy + DuckDNS module untuk Android arm64
# Menggunakan Go bawaan laptop (tanpa NDK atau Android Studio!)

Write-Host "Building Caddy for Android arm64..." -ForegroundColor Cyan

$env:GOOS = "android"
$env:GOARCH = "arm64"
$env:CGO_ENABLED = "0"

$xcaddyPath = "$env:USERPROFILE\go\bin\xcaddy.exe"
if (-not (Test-Path $xcaddyPath)) {
    Write-Host "Installing xcaddy..." -ForegroundColor Yellow
    go install github.com/caddyserver/xcaddy/cmd/xcaddy@latest
}

& $xcaddyPath build `
    --with github.com/caddy-dns/duckdns `
    --output ./caddy-android-arm64

if (Test-Path "./caddy-android-arm64") {
    Write-Host "Caddy built successfully!" -ForegroundColor Green
    Copy-Item "./caddy-android-arm64" "./app/src/main/jniLibs/arm64-v8a/libcaddy.so" -Force
    Write-Host "Copied to app/src/main/jniLibs/arm64-v8a/libcaddy.so" -ForegroundColor Green
} else {
    Write-Host "Build failed." -ForegroundColor Red
}

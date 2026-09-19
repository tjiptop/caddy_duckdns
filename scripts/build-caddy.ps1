# Script untuk cross-compile Caddy + DuckDNS module untuk Android arm64 & armv7 (Android 5, 7, 8+)
# Menggunakan Go bawaan laptop (tanpa NDK atau Android Studio!)

$xcaddyPath = "$env:USERPROFILE\go\bin\xcaddy.exe"
if (-not (Test-Path $xcaddyPath)) {
    Write-Host "Installing xcaddy..." -ForegroundColor Yellow
    go install github.com/caddyserver/xcaddy/cmd/xcaddy@latest
}

# 1. Build ARM64 (Modern Android 8+)
Write-Host "Building Caddy for Android ARM64..." -ForegroundColor Cyan
$env:GOOS = "android"
$env:GOARCH = "arm64"
$env:CGO_ENABLED = "0"
& $xcaddyPath build --with github.com/caddy-dns/duckdns --output ./caddy-android-arm64

if (Test-Path "./caddy-android-arm64") {
    New-Item -ItemType Directory -Force -Path "./app/src/main/jniLibs/arm64-v8a" | Out-Null
    Copy-Item "./caddy-android-arm64" "./app/src/main/jniLibs/arm64-v8a/libcaddy.so" -Force
    Write-Host "ARM64 built & copied to app/src/main/jniLibs/arm64-v8a/libcaddy.so" -ForegroundColor Green
}

# 2. Build ARMv7 32-bit (Android 5.0, 7.0 & legacy 32-bit devices)
Write-Host "Building Caddy for Linux/Android ARMv7 (32-bit)..." -ForegroundColor Cyan
$env:GOOS = "linux"
$env:GOARCH = "arm"
$env:GOARM = "7"
$env:CGO_ENABLED = "0"
& $xcaddyPath build --with github.com/caddy-dns/duckdns --output ./caddy-linux-armv7

if (Test-Path "./caddy-linux-armv7") {
    New-Item -ItemType Directory -Force -Path "./app/src/main/jniLibs/armeabi-v7a" | Out-Null
    Copy-Item "./caddy-linux-armv7" "./app/src/main/jniLibs/armeabi-v7a/libcaddy.so" -Force
    Write-Host "ARMv7 built & copied to app/src/main/jniLibs/armeabi-v7a/libcaddy.so" -ForegroundColor Green
}

Write-Host "All architectures compiled successfully!" -ForegroundColor Green

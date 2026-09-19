<#
.SYNOPSIS
    Background Service Runner untuk Caddy Proxy di Windows.
    Dijalankan secara otomatis oleh Task Scheduler saat sistem booting (ONSTART).
#>

$installDir = $PSScriptRoot
if (-not $installDir) {
    $installDir = "D:\AIDEV\caddy\windows"
}
Set-Location $installDir

$logFile = Join-Path $installDir "service.log"
$configFile = Join-Path $installDir "caddy_proxy_config.json"
$caddyExe = Join-Path $installDir "caddy.exe"
$caddyFile = Join-Path $installDir "Caddyfile"

function Log-Message ($msg) {
    $time = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$time] $msg"
    Add-Content -Path $logFile -Value $line -ErrorAction SilentlyContinue
}

Log-Message "=== Caddy Service Runner Memulai (System Boot) ==="

# 1. Tunggu hingga koneksi jaringan/internet tersedia (maksimal 60 detik)
Log-Message "Menunggu adapter jaringan dan koneksi internet aktif..."
$connected = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $ipEntry = [System.Net.Dns]::GetHostAddresses("www.duckdns.org")
        if ($ipEntry.Count -gt 0) {
            $connected = $true
            break
        }
    } catch {}
    Start-Sleep -Seconds 2
}

if ($connected) {
    Log-Message "Koneksi jaringan terdeteksi!"
} else {
    Log-Message "Peringatan: DNS belum responsif setelah 60 detik, melanjutkan start..."
}

# 2. Baca konfigurasi dari caddy_proxy_config.json (atau default)
$domain = "tjipto.duckdns.org"
$token = ""
$host = "127.0.0.1"
$port = "8090"
$listenPort = "8443"
$manualIp = ""

if (Test-Path $configFile) {
    try {
        $json = Get-Content $configFile -Raw | ConvertFrom-Json
        if ($json.domain) { $domain = $json.domain }
        if ($json.token) { $token = $json.token }
        if ($json.host) { $host = $json.host }
        if ($json.port) { $port = $json.port }
        if ($json.listenPort) { $listenPort = $json.listenPort }
        if ($json.manualIp) { $manualIp = $json.manualIp }
        Log-Message "Konfigurasi dimuat dari $configFile"
    } catch {
        Log-Message "Gagal mem-parsing config file: $($_.Exception.Message)"
    }
} else {
    Log-Message "File konfigurasi tidak ditemukan, menggunakan nilai default."
}

# 3. Dapatkan IP lokal atau gunakan manual IP
$targetIp = $manualIp
if ([string]::IsNullOrWhiteSpace($targetIp)) {
    try {
        foreach ($ni in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($ni.OperationalStatus -eq [System.Net.NetworkInformation.OperationalStatus]::Up -and $ni.NetworkInterfaceType -ne [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback) {
                foreach ($u in $ni.GetIPProperties().UnicastAddresses) {
                    if ($u.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
                        $candidate = $u.Address.ToString()
                        if (-not $candidate.StartsWith("169.254")) {
                            $targetIp = $candidate
                            break
                        }
                    }
                }
            }
            if ($targetIp) { break }
        }
    } catch {}
    if (-not $targetIp) { $targetIp = "127.0.0.1" }
}
Log-Message "Target IP lokal: $targetIp"

# 4. Update DuckDNS jika token tersedia
if (-not [string]::IsNullOrWhiteSpace($token)) {
    $subdomain = $domain.Replace(".duckdns.org", "").TrimEnd('.')
    $updateUrl = "https://www.duckdns.org/update?domains=$subdomain&token=$token&ip=$targetIp"
    try {
        Log-Message "Mengupdate DuckDNS: $subdomain -> $targetIp"
        $resp = (New-Object System.Net.WebClient).DownloadString($updateUrl)
        Log-Message "Respon DuckDNS: $resp"
    } catch {
        Log-Message "Gagal update DuckDNS: $($_.Exception.Message)"
    }
} else {
    Log-Message "Peringatan: Token DuckDNS belum diisi. Pastikan telah mengisi token di CaddyProxy.exe."
}

# 5. Buat Caddyfile
$caddyConfig = @"
{
    admin off
    auto_https disable_redirects
}

$domain`:$listenPort {
    tls {
        dns duckdns $token
        resolvers 8.8.8.8 8.8.4.4
    }
    reverse_proxy $host`:$port
}
"@
Set-Content -Path $caddyFile -Value $caddyConfig -Encoding UTF8
Log-Message "Caddyfile berhasil dibuat."

# 6. Jalankan caddy.exe
if (Test-Path $caddyExe) {
    Log-Message "Menjalankan $caddyExe run --config $caddyFile..."
    $process = Start-Process -FilePath $caddyExe `
        -ArgumentList "run --config `"$caddyFile`"" `
        -WorkingDirectory $installDir `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput (Join-Path $installDir "caddy_stdout.log") `
        -RedirectStandardError (Join-Path $installDir "caddy_stderr.log")

    Log-Message "Caddy berjalan dengan Process ID: $($process.Id)"
    $process.WaitForExit()
    Log-Message "Caddy berhenti dengan exit code: $($process.ExitCode)"
} else {
    Log-Message "ERROR: caddy.exe tidak ditemukan di $caddyExe"
}

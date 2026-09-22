package com.caddy.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.KeyStore
import java.security.cert.X509Certificate
import android.util.Base64

class CaddyService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var caddyProcess: Process? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_DOMAIN = "EXTRA_DOMAIN"
        const val EXTRA_TOKEN = "EXTRA_TOKEN"
        const val EXTRA_BACKEND_HOST = "EXTRA_BACKEND_HOST"
        const val EXTRA_BACKEND_PORT = "EXTRA_BACKEND_PORT"
        const val EXTRA_LISTEN_PORT = "EXTRA_LISTEN_PORT"
        const val EXTRA_MANUAL_IP = "EXTRA_MANUAL_IP"
        const val EXTRA_DISABLE_LOG = "EXTRA_DISABLE_LOG"

        const val CHANNEL_ID = "caddy_proxy_channel"
        const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set

        var startTime: Long = 0L
            private set

        val fullLogBuffer = StringBuilder()

        fun getLogs(): String = synchronized(fullLogBuffer) {
            fullLogBuffer.toString()
        }

        fun clearLogs() = synchronized(fullLogBuffer) {
            fullLogBuffer.setLength(0)
        }

        fun appendLog(msg: String) = synchronized(fullLogBuffer) {
            fullLogBuffer.append(msg).append("\n")
            val lines = fullLogBuffer.lines()
            if (lines.size > 600) {
                val trimmed = lines.takeLast(500).joinToString("\n")
                fullLogBuffer.setLength(0)
                fullLogBuffer.append(trimmed).append("\n")
            }
        }

        var logListener: ((String) -> Unit)? = null
        var statusListener: ((Boolean, String) -> Unit)? = null

        fun emitLog(msg: String) {
            appendLog(msg)
            logListener?.invoke(msg)
        }

        private fun emitStatus(running: Boolean, text: String) {
            isRunning = running
            statusListener?.invoke(running, text)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                startTime = System.currentTimeMillis()
                val domain = intent.getStringExtra(EXTRA_DOMAIN) ?: ""
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
                val backendHost = intent.getStringExtra(EXTRA_BACKEND_HOST)?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
                val backendPort = intent.getStringExtra(EXTRA_BACKEND_PORT) ?: "8090"
                val listenPort = intent.getStringExtra(EXTRA_LISTEN_PORT) ?: "8443"
                val manualIp = intent.getStringExtra(EXTRA_MANUAL_IP) ?: ""
                val disableLog = intent.getBooleanExtra(EXTRA_DISABLE_LOG, true)

                startForeground(NOTIFICATION_ID, buildNotification("Running HTTPS: $domain:$listenPort"))
                emitStatus(true, "Starting Caddy...")
                startCaddy(domain, token, backendHost, backendPort, listenPort, manualIp, disableLog)
            }
            ACTION_STOP -> {
                startTime = 0L
                stopCaddy()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startCaddy(
        domain: String,
        token: String,
        backendHost: String,
        backendPort: String,
        listenPort: String,
        manualIp: String,
        disableLog: Boolean
    ) {
        serviceScope.launch {
            try {
                // Sanitize domain (fix dnsduck typo and add .duckdns.org suffix if missing)
                var cleanDomain = domain.trim().replace("dnsduck.org", "duckdns.org")
                if (cleanDomain.isNotBlank() && !cleanDomain.contains(".")) {
                    cleanDomain = "$cleanDomain.duckdns.org"
                }

                // Clean backend host and ports
                val cleanBackendHost = backendHost.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .trimEnd('/')
                    .ifBlank { "127.0.0.1" }
                val cleanBackendPort = backendPort.trim().ifBlank { "8090" }
                val cleanListenPort = listenPort.trim().ifBlank { "8443" }

                // 1. Determine local IP and update DuckDNS
                val localIp = if (manualIp.isNotBlank()) {
                    manualIp.trim()
                } else {
                    NetworkHelper.getLocalIpAddress()
                }
                emitLog("Using Hotspot/Target IP: $localIp")
                if (cleanDomain.isNotBlank() && token.isNotBlank()) {
                    emitLog("Updating DuckDNS record for $cleanDomain...")
                    val (_, updateMsg) = DuckDnsHelper.updateIp(cleanDomain, token, localIp)
                    emitLog(updateMsg)
                }

                // 2. Prepare Caddy directories
                val caddyDataDir = File(filesDir, "caddy_data").apply { mkdirs() }
                val caddyConfigDir = File(filesDir, "caddy_config").apply { mkdirs() }
                val certsDir = File(filesDir, "certs").apply { mkdirs() }
                val certFile = File(certsDir, "$cleanDomain.crt")
                val keyFile = File(certsDir, "$cleanDomain.key")

                // Auto-restore SSL certificates from SharedPreferences if missing
                val prefs = getSharedPreferences("caddy_proxy_prefs", Context.MODE_PRIVATE)
                if (!certFile.exists() || certFile.length() == 0L || !keyFile.exists() || keyFile.length() == 0L) {
                    val savedCrt = prefs.getString("saved_cert_$cleanDomain", null)
                    val savedKey = prefs.getString("saved_key_$cleanDomain", null)
                    if (!savedCrt.isNullOrBlank() && !savedKey.isNullOrBlank()) {
                        certFile.writeText(savedCrt)
                        keyFile.writeText(savedKey)
                        emitLog("Memulihkan sertifikat SSL dari penyimpanan permanen untuk $cleanDomain")
                    }
                }

                // Extract bundled certificates from APK assets if available
                try {
                    val assetList = assets.list("certs") ?: emptyArray()
                    if ("$cleanDomain.crt" in assetList && (!certFile.exists() || certFile.length() == 0L)) {
                        assets.open("certs/$cleanDomain.crt").use { input ->
                            certFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        assets.open("certs/$cleanDomain.key").use { input ->
                            keyFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        emitLog("Installed pre-issued SSL certificate for $cleanDomain from assets")
                    }
                } catch (e: Exception) {
                    emitLog("Note on assets: ${e.message}")
                }

                // Start Client Certificate Download Portal if enabled
                if (prefs.getBoolean("enable_portal", true)) {
                    CertServerManager.start(this@CaddyService, onLog = { emitLog(it) })
                }

                val hasCustomCert = certFile.exists() && certFile.length() > 0L && keyFile.exists() && keyFile.length() > 0L

                // 3. Write Caddyfile
                val caddyFile = File(filesDir, "Caddyfile")
                val tlsBlock = if (hasCustomCert) {
                    emitLog("Menggunakan sertifikat SSL lokal: ${certFile.name} (${certFile.length()} bytes)")
                    "tls ${certFile.absolutePath} ${keyFile.absolutePath}"
                } else if (token.isNotBlank()) {
                    emitLog("Meminta sertifikat Let's Encrypt resmi via DuckDNS otomatis...")
                    """tls {
        dns duckdns $token
        resolvers 8.8.8.8 1.1.1.1 8.8.4.4
    }"""
                } else {
                    emitLog("Mode tanpa token: Menggunakan sertifikat SSL internal mandiri")
                    "tls internal"
                }

                val logBlock = if (disableLog) {
                    emitLog("HTTP access logging disabled for optimal performance.")
                    ""
                } else {
                    """
    log {
        output stdout
        format console
    }"""
                }

                val configContent = """
{
    admin off
    auto_https disable_redirects
}

$cleanDomain:$cleanListenPort, :$cleanListenPort {
    $tlsBlock$logBlock
    reverse_proxy $cleanBackendHost:$cleanBackendPort {
        header_up Host {host}
        header_up X-Real-IP {remote_host}
        header_up X-Forwarded-Proto https
    }
}
""".trimIndent()
                caddyFile.writeText(configContent)
                emitLog("Caddyfile generated.")

                // 4. Locate binary
                val nativeLibDir = applicationInfo.nativeLibraryDir
                var binaryFile = File(nativeLibDir, "libcaddy.so")

                // Fallback to internal storage if needed (common on custom Android 5 ROMs where nativeLibDir is noexec)
                if (!binaryFile.exists() || !binaryFile.canExecute()) {
                    val internalBin = File(filesDir, "libcaddy.so")
                    if (!internalBin.exists() || internalBin.length() == 0L) {
                        try {
                            if (binaryFile.exists()) {
                                binaryFile.copyTo(internalBin, overwrite = true)
                            } else {
                                assets.open("caddy").use { input ->
                                    internalBin.outputStream().use { output -> input.copyTo(output) }
                                }
                            }
                        } catch (e: Exception) {
                            // Ignored if asset not present
                        }
                    }
                    if (internalBin.exists()) {
                        internalBin.setExecutable(true, false)
                        if (internalBin.canExecute()) {
                            binaryFile = internalBin
                        }
                    }
                }

                if (!binaryFile.exists()) {
                    emitLog("ERROR: Caddy binary not found at ${binaryFile.absolutePath}")
                    emitStatus(false, "Binary not found")
                    return@launch
                }
                binaryFile.setExecutable(true, false)

                // Export System CA Certificates with embedded ISRG Root X1 & X2 so Go crypto/x509 can connect
                val caCertFile = File(filesDir, "ca-certificates.crt")
                exportSystemCaCerts(caCertFile)

                emitLog("Starting Caddy process from: ${binaryFile.absolutePath}")

                // 5. Execute Caddy process
                val pb = ProcessBuilder(
                    binaryFile.absolutePath,
                    "run",
                    "--adapter",
                    "caddyfile",
                    "--config",
                    caddyFile.absolutePath
                )

                val env = pb.environment()
                env["HOME"] = filesDir.absolutePath
                env["XDG_DATA_HOME"] = caddyDataDir.absolutePath
                env["XDG_CONFIG_HOME"] = caddyConfigDir.absolutePath
                env["GODEBUG"] = "netdns=cgo+2"
                if (caCertFile.exists() && caCertFile.length() > 0) {
                    env["SSL_CERT_FILE"] = caCertFile.absolutePath
                    env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                }

                val process = pb.start()
                caddyProcess = process
                emitStatus(true, "Running (:$cleanListenPort -> :$cleanBackendPort)")

                // Auto-save Let's Encrypt certificate to persistent storage once issued
                if (token.isNotBlank()) {
                    launch(Dispatchers.IO) {
                        emitLog("⏳ Menunggu penerbitan sertifikat SSL otomatis dari Let's Encrypt...")
                        for (i in 1..60) {
                            kotlinx.coroutines.delay(3000)
                            val certPair = findDomainCerts(caddyDataDir, cleanDomain)
                            if (certPair != null) {
                                val (autoCrt, autoKey) = certPair
                                val crtText = autoCrt.readText()
                                val keyText = autoKey.readText()
                                prefs.edit()
                                    .putString("saved_cert_$cleanDomain", crtText)
                                    .putString("saved_key_$cleanDomain", keyText)
                                    .apply()
                                autoCrt.copyTo(certFile, overwrite = true)
                                autoKey.copyTo(keyFile, overwrite = true)
                                emitLog("✅ Sertifikat Let's Encrypt berhasil diterbitkan & disimpan permanen! (${autoCrt.length()} bytes)")
                                emitStatus(true, "Running HTTPS (SSL Aktif)")
                                break
                            }
                            if (i % 5 == 0) {
                                emitLog("⏳ Menunggu propagasi DNS DuckDNS & validasi Let's Encrypt ($i/60)...")
                            }
                        }
                    }
                }

                // Read stdout & stderr
                launch {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let { emitLog("[Caddy] $it") }
                        }
                    }
                }

                launch {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let { emitLog("[Caddy Log] $it") }
                        }
                    }
                }

                val exitCode = process.waitFor()
                emitLog("Caddy stopped with exit code $exitCode")
                emitStatus(false, "Stopped")
            } catch (e: Exception) {
                emitLog("Error executing Caddy: ${e.message}")
                emitStatus(false, "Error: ${e.message}")
            }
        }
    }

    private fun stopCaddy() {
        try {
            caddyProcess?.destroy()
            caddyProcess = null
            emitLog("Caddy process terminated.")
            emitStatus(false, "Stopped")
        } catch (e: Exception) {
            emitLog("Error stopping Caddy: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Caddy Reverse Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status of Caddy reverse proxy service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flagImmutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

        val stopIntent = Intent(this, CaddyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caddy Reverse Proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private val ISRG_ROOT_X1_AND_X2_PEM = """
-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQsw
CQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2gg
R3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00
MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBT
ZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYw
EAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW
+1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9
ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T
AQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZI
zj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdW
tL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1
/q4AaOeMSQ+2b1tbFfLn
-----END CERTIFICATE-----
""".trimIndent()

    private fun findDomainCerts(dataDir: File, domain: String): Pair<File, File>? {
        if (!dataDir.exists()) return null
        var crtFile: File? = null
        var keyFile: File? = null
        val cleanName = domain.lowercase()

        try {
            dataDir.walkTopDown().forEach { f ->
                if (f.isFile && f.length() > 0) {
                    val fn = f.name.lowercase()
                    if (fn == "$cleanName.crt" || fn == "$cleanName.cer") {
                        crtFile = f
                    } else if (fn == "$cleanName.key") {
                        keyFile = f
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored
        }

        return if (crtFile != null && keyFile != null) Pair(crtFile!!, keyFile!!) else null
    }

    private fun exportSystemCaCerts(outputFile: File) {
        try {
            val sb = StringBuilder()

            // 0. Always include ISRG Root X1 and ISRG Root X2 (critical for Android 5/7)
            sb.append(ISRG_ROOT_X1_AND_X2_PEM).append("\n\n")

            // 1. Export from AndroidCAStore
            try {
                val ks = KeyStore.getInstance("AndroidCAStore")
                ks.load(null, null)
                val aliases = ks.aliases()
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                    val encoded = Base64.encodeToString(cert.encoded, Base64.DEFAULT)
                    sb.append("-----BEGIN CERTIFICATE-----\n")
                    sb.append(encoded)
                    sb.append("-----END CERTIFICATE-----\n\n")
                }
            } catch (e: Exception) {
                emitLog("Note: AndroidCAStore error: ${e.message}")
            }

            // 2. Export from /system/etc/security/cacerts/
            try {
                val dir = File("/system/etc/security/cacerts")
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && f.length() > 0) {
                            try {
                                val content = f.readText()
                                if (content.contains("BEGIN CERTIFICATE")) {
                                    sb.append(content).append("\n\n")
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {}

            if (sb.isNotEmpty()) {
                outputFile.writeText(sb.toString())
                emitLog("CA certificates exported with ISRG Root X1/X2 (${outputFile.length()} bytes)")
            }
        } catch (e: Exception) {
            emitLog("Error exporting CA certificates: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopCaddy()
        serviceScope.cancel()
        super.onDestroy()
    }
}

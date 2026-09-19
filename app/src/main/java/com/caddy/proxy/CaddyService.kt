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

        const val CHANNEL_ID = "caddy_proxy_channel"
        const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set

        var logListener: ((String) -> Unit)? = null
        var statusListener: ((Boolean, String) -> Unit)? = null

        private fun emitLog(msg: String) {
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
                val domain = intent.getStringExtra(EXTRA_DOMAIN) ?: "tjipto.duckdns.org"
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
                val backendHost = intent.getStringExtra(EXTRA_BACKEND_HOST)?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
                val backendPort = intent.getStringExtra(EXTRA_BACKEND_PORT) ?: "8090"
                val listenPort = intent.getStringExtra(EXTRA_LISTEN_PORT) ?: "8443"
                val manualIp = intent.getStringExtra(EXTRA_MANUAL_IP) ?: ""

                startForeground(NOTIFICATION_ID, buildNotification("Running HTTPS: $domain:$listenPort"))
                emitStatus(true, "Starting Caddy...")
                startCaddy(domain, token, backendHost, backendPort, listenPort, manualIp)
            }
            ACTION_STOP -> {
                stopCaddy()
                stopForeground(STOP_FOREGROUND_REMOVE)
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
        manualIp: String
    ) {
        serviceScope.launch {
            try {
                // 1. Determine local IP and update DuckDNS
                val localIp = if (manualIp.isNotBlank()) {
                    manualIp.trim()
                } else {
                    NetworkHelper.getLocalIpAddress()
                }
                emitLog("Using Hotspot/Target IP: $localIp")
                emitLog("Updating DuckDNS record for $domain...")

                val (ok, updateMsg) = DuckDnsHelper.updateIp(domain, token, localIp)
                emitLog(updateMsg)

                // 2. Prepare Caddy directories
                val caddyDataDir = File(filesDir, "caddy_data").apply { mkdirs() }
                val caddyConfigDir = File(filesDir, "caddy_config").apply { mkdirs() }

                // 3. Write Caddyfile
                val caddyFile = File(filesDir, "Caddyfile")
                val configContent = """
{
    admin off
    auto_https disable_redirects
}

$domain:$listenPort {
    tls {
        dns duckdns $token
        resolvers 8.8.8.8 8.8.4.4
    }
    reverse_proxy $backendHost:$backendPort
}
""".trimIndent()
                caddyFile.writeText(configContent)
                emitLog("Caddyfile generated.")

                // 4. Locate binary
                val nativeLibDir = applicationInfo.nativeLibraryDir
                var binaryFile = File(nativeLibDir, "libcaddy.so")

                if (!binaryFile.exists()) {
                    // Fallback to internal storage if needed
                    binaryFile = File(filesDir, "libcaddy.so")
                }

                if (!binaryFile.exists()) {
                    emitLog("ERROR: Caddy binary not found at ${binaryFile.absolutePath}")
                    emitStatus(false, "Binary not found")
                    return@launch
                }

                binaryFile.setExecutable(true, false)

                emitLog("Starting Caddy process from: ${binaryFile.absolutePath}")

                // 5. Execute Caddy process
                val pb = ProcessBuilder(
                    binaryFile.absolutePath,
                    "run",
                    "--config",
                    caddyFile.absolutePath
                )

                val env = pb.environment()
                env["HOME"] = filesDir.absolutePath
                env["XDG_DATA_HOME"] = caddyDataDir.absolutePath
                env["XDG_CONFIG_HOME"] = caddyConfigDir.absolutePath

                val process = pb.start()
                caddyProcess = process
                emitStatus(true, "Running (:8443 -> :$backendPort)")

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
        val stopIntent = Intent(this, CaddyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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

    override fun onDestroy() {
        stopCaddy()
        serviceScope.cancel()
        super.onDestroy()
    }
}

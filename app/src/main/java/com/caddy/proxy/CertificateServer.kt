package com.caddy.proxy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class CertificateServer(
    private val context: Context,
    private val port: Int = 8080,
    private val onLog: (String) -> Unit = {},
    private val onCertReceived: (domain: String, crt: String, key: String) -> Unit = { _, _, _ -> }
) {
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs: SharedPreferences = context.getSharedPreferences("caddy_proxy_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "CertificateServer"
    }

    val isRunning: Boolean
        get() = serverSocket != null && !serverSocket!!.isClosed

    fun start() {
        if (isRunning) return

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                onLog("Portal Sertifikat HTTP aktif di port $port")
                Log.d(TAG, "Server started on port $port")

                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val client = serverSocket!!.accept()
                        scope.launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting server on port $port: ${e.message}")
                onLog("Gagal membuka portal port $port: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            onLog("Portal Sertifikat dimatikan.")
            Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: run {
                socket.close()
                return
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }

            val method = parts[0].uppercase()
            val path = parts[1].split("?")[0]

            // Read HTTP headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(":")
                if (colonIdx > 0) {
                    val hName = line!!.substring(0, colonIdx).trim().lowercase()
                    val hVal = line!!.substring(colonIdx + 1).trim()
                    headers[hName] = hVal
                }
            }

            when {
                // 1. Download certificate file directly
                method == "GET" && (path == "/cert.crt" || path == "/cert" || path == "/ca.crt" || path.endsWith(".crt")) -> {
                    handleDownloadCert(output)
                }

                // 2. Upload certificate from PC / admin
                method == "POST" && (path == "/upload-cert" || path == "/api/cert") -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(body, read, contentLength - read)
                        if (count == -1) break
                        read += count
                    }
                    val bodyStr = String(body, 0, read)
                    handleUploadCert(output, bodyStr)
                }

                // 3. Status API
                method == "GET" && path == "/api/status" -> {
                    handleStatusApi(output)
                }

                // 4. Client web portal landing page
                method == "GET" -> {
                    handleWebPortal(output)
                }

                else -> {
                    sendResponse(output, 404, "Not Found", "text/plain", "404 Not Found".toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun getActiveDomain(): String {
        val domain = prefs.getString("domain", "") ?: ""
        var clean = domain.trim().replace("dnsduck.org", "duckdns.org")
        if (clean.isNotBlank() && !clean.contains(".")) {
            clean = "$clean.duckdns.org"
        }
        return if (clean.isNotBlank()) clean else "absenku.duckdns.org"
    }

    private fun getCertContent(): String? {
        val domain = getActiveDomain()
        // 1. Check SharedPreferences first
        val saved = prefs.getString("saved_cert_$domain", null)
        if (!saved.isNullOrBlank()) return saved

        // 2. Check internal filesDir
        val certFile = File(File(context.filesDir, "certs"), "$domain.crt")
        if (certFile.exists() && certFile.length() > 0L) {
            return certFile.readText()
        }

        // 3. Check any .crt file in certs dir
        val certsDir = File(context.filesDir, "certs")
        if (certsDir.exists()) {
            val anyCrt = certsDir.listFiles { _, name -> name.endsWith(".crt") }?.firstOrNull()
            if (anyCrt != null && anyCrt.length() > 0L) {
                return anyCrt.readText()
            }
        }

        return null
    }

    private fun handleDownloadCert(output: OutputStream) {
        val certText = getCertContent()
        val domain = getActiveDomain()

        if (certText.isNullOrBlank()) {
            val msg = "Sertifikat SSL belum tersedia di server ini. Silakan pasang sertifikat pada aplikasi Caddy terlebih dahulu."
            sendResponse(output, 404, "Not Found", "text/plain; charset=utf-8", msg.toByteArray(Charsets.UTF_8))
            return
        }

        val certBytes = certText.toByteArray(Charsets.UTF_8)
        val extraHeaders = mapOf(
            "Content-Disposition" to "attachment; filename=\"$domain.crt\"",
            "Cache-Control" to "no-cache"
        )
        sendResponse(output, 200, "OK", "application/x-x509-ca-cert", certBytes, extraHeaders)
    }

    private fun handleUploadCert(output: OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val domain = json.optString("domain", "").trim()
            val crt = json.optString("crt", "").trim()
            val key = json.optString("key", "").trim()

            if (domain.isBlank() || crt.isBlank() || key.isBlank()) {
                val err = JSONObject().apply {
                    put("status", "error")
                    put("message", "Field domain, crt, dan key wajib diisi!")
                }.toString()
                sendResponse(output, 400, "Bad Request", "application/json", err.toByteArray())
                return
            }

            // Save to SharedPreferences (permanent)
            prefs.edit()
                .putString("saved_cert_$domain", crt)
                .putString("saved_key_$domain", key)
                .putString("domain", domain)
                .apply()

            // Save to files/certs directory
            val certsDir = File(context.filesDir, "certs").apply { mkdirs() }
            File(certsDir, "$domain.crt").writeText(crt)
            File(certsDir, "$domain.key").writeText(key)

            onLog("Sertifikat SSL untuk $domain berhasil diterima dan disimpan permanen!")
            onCertReceived(domain, crt, key)

            val success = JSONObject().apply {
                put("status", "ok")
                put("message", "Sertifikat untuk $domain berhasil dipasang.")
                put("domain", domain)
                put("cert_bytes", crt.length)
            }.toString()

            sendResponse(output, 200, "OK", "application/json", success.toByteArray())
        } catch (e: Exception) {
            val err = JSONObject().apply {
                put("status", "error")
                put("message", e.message ?: "Unknown error")
            }.toString()
            sendResponse(output, 500, "Internal Server Error", "application/json", err.toByteArray())
        }
    }

    private fun handleStatusApi(output: OutputStream) {
        val domain = getActiveDomain()
        val hasCert = !getCertContent().isNullOrBlank()
        val json = JSONObject().apply {
            put("running", true)
            put("domain", domain)
            put("has_cert", hasCert)
            put("https_listen_port", prefs.getString("listen_port", "8443"))
            put("backend_port", prefs.getString("backend_port", "8090"))
        }.toString()

        sendResponse(output, 200, "OK", "application/json", json.toByteArray())
    }

    private fun handleWebPortal(output: OutputStream) {
        val domain = getActiveDomain()
        val certContent = getCertContent()
        val hasCert = !certContent.isNullOrBlank()
        val listenPort = prefs.getString("listen_port", "8443") ?: "8443"

        val statusBadge = if (hasCert) {
            """<div class="badge success">✅ Sertifikat SSL Aktif ($domain)</div>"""
        } else {
            """<div class="badge warning">⚠️ Sertifikat SSL Belum Dipasang di Server</div>"""
        }

        val downloadBtn = if (hasCert) {
            """<a href="/cert.crt" class="btn primary">⬇️ Download Sertifikat SSL (.crt)</a>"""
        } else {
            """<button class="btn disabled" disabled>Sertifikat Belum Tersedia</button>"""
        }

        val html = """
<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Portal Sertifikat SSL - $domain</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body { background: #0f172a; color: #f8fafc; padding: 20px; line-height: 1.6; }
        .container { max-width: 580px; margin: 0 auto; }
        .card { background: #1e293b; border-radius: 14px; padding: 24px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); border: 1px solid #334155; margin-bottom: 20px; }
        h1 { font-size: 22px; margin-bottom: 8px; color: #38bdf8; display: flex; align-items: center; gap: 8px; }
        p.subtitle { color: #94a3b8; font-size: 14px; margin-bottom: 18px; }
        .badge { display: inline-block; padding: 6px 14px; border-radius: 20px; font-size: 13px; font-weight: 600; margin-bottom: 18px; }
        .badge.success { background: rgba(34, 197, 94, 0.2); color: #4ade80; border: 1px solid #22c55e; }
        .badge.warning { background: rgba(234, 179, 8, 0.2); color: #facc15; border: 1px solid #eab308; }
        .btn { display: block; text-align: center; padding: 14px 20px; border-radius: 10px; font-size: 16px; font-weight: bold; text-decoration: none; transition: 0.2s; border: none; cursor: pointer; width: 100%; }
        .btn.primary { background: #0284c7; color: white; box-shadow: 0 4px 14px rgba(2, 132, 199, 0.4); }
        .btn.primary:hover { background: #0369a1; }
        .btn.disabled { background: #475569; color: #94a3b8; cursor: not-allowed; }
        .guide-section { margin-top: 24px; }
        .guide-title { font-size: 16px; font-weight: bold; color: #e2e8f0; margin-bottom: 12px; }
        .tab-box { background: #0f172a; border-radius: 8px; padding: 16px; margin-bottom: 12px; border-left: 4px solid #38bdf8; }
        .tab-box h3 { font-size: 14px; color: #38bdf8; margin-bottom: 8px; }
        ol { padding-left: 20px; font-size: 13px; color: #cbd5e1; }
        li { margin-bottom: 6px; }
        .link-box { background: #0284c7; color: white; padding: 12px; border-radius: 8px; margin-top: 16px; text-align: center; }
        .link-box a { color: #fef08a; font-weight: bold; text-decoration: underline; }
        footer { text-align: center; font-size: 12px; color: #64748b; margin-top: 20px; }
    </style>
</head>
<body>
<div class="container">
    <div class="card">
        <h1>🔒 Portal Sertifikat SSL</h1>
        <p class="subtitle">Unduh dan pasang sertifikat keamanan untuk mengakses aplikasi web tanpa peringatan keamanan pada jaringan hotspot / intranet.</p>
        
        $statusBadge
        
        $downloadBtn
        
        <div class="link-box">
            Setelah sertifikat terpasang, buka aplikasi di:<br>
            <a href="https://$domain:$listenPort">https://$domain:$listenPort</a>
        </div>
    </div>

    <div class="card guide-section">
        <div class="guide-title">📖 Panduan Memasang Sertifikat (.crt):</div>
        
        <div class="tab-box">
            <h3>📱 Android:</h3>
            <ol>
                <li>Ketuk tombol unduh di atas untuk mengunduh <b>$domain.crt</b>.</li>
                <li>Buka <b>Pengaturan HP</b> &rarr; cari <b>"Sertifikat"</b> atau <b>"Enkripsi & Kredensial"</b>.</li>
                <li>Pilih <b>Pasang dari penyimpanan</b> &rarr; pilih <b>Sertifikat CA</b> (atau <b>Sertifikat Pengguna</b>).</li>
                <li>Pilih file <code>$domain.crt</code> yang baru saja diunduh & konfirmasi pemasangan.</li>
            </ol>
        </div>

        <div class="tab-box">
            <h3>💻 Windows:</h3>
            <ol>
                <li>Buka file <code>$domain.crt</code> yang telah diunduh.</li>
                <li>Klik <b>Install Certificate...</b> &rarr; pilih <b>Current User</b> (atau Local Machine).</li>
                <li>Pilih <b>Place all certificates in the following store</b> &rarr; klik <b>Browse</b> &rarr; pilih <b>Trusted Root Certification Authorities</b>.</li>
                <li>Klik Next & Finish &rarr; konfirmasi Yes.</li>
            </ol>
        </div>

        <div class="tab-box">
            <h3>🍏 iPhone / iPad (iOS):</h3>
            <ol>
                <li>Buka halaman ini dan unduh file melalui browser <b>Safari</b>.</li>
                <li>Buka <b>Settings</b> &rarr; ketuk <b>Profile Downloaded</b> &rarr; <b>Install</b>.</li>
                <li>Buka <b>Settings &rarr; General &rarr; About &rarr; Certificate Trust Settings</b>.</li>
                <li>Aktifkan sakelar hijau untuk sertifikat <b>$domain</b>.</li>
            </ol>
        </div>
    </div>
    
    <footer>Caddy HTTPS Reverse Proxy &bull; Portal Sertifikat Offline</footer>
</div>
</body>
</html>
        """.trimIndent()

        sendResponse(output, 200, "OK", "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val sb = java.lang.StringBuilder()
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type\r\n")
        for ((k, v) in extraHeaders) {
            sb.append("$k: $v\r\n")
        }
        sb.append("\r\n")

        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }
}

object CertServerManager {
    private var instance: CertificateServer? = null

    @Synchronized
    fun start(
        context: Context,
        onLog: (String) -> Unit = {},
        onCertReceived: (String, String, String) -> Unit = { _, _, _ -> }
    ) {
        if (instance == null || !instance!!.isRunning) {
            instance = CertificateServer(context.applicationContext, 8080, onLog, onCertReceived)
            instance?.start()
        }
    }

    @Synchronized
    fun stop() {
        instance?.stop()
        instance = null
    }

    val isRunning: Boolean
        get() = instance?.isRunning == true
}


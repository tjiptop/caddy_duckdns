package com.caddy.proxy

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

object LogServer {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    const val PORT = 8088
    var certDirProvider: (() -> File)? = null

    fun start(certDir: (() -> File)? = null, logProvider: () -> String) {
        if (isRunning) return
        isRunning = true
        if (certDir != null) certDirProvider = certDir

        Thread {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                while (isRunning) {
                    try {
                        val socket = ss.accept()
                        Thread {
                            handleClient(socket, logProvider)
                        }.start()
                    } catch (e: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply {
            isDaemon = true
            name = "LogServer-Thread"
            start()
        }
    }

    private fun handleClient(socket: Socket, logProvider: () -> String) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val reqLine = reader.readLine() ?: return
                var contentLength = 0
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) break
                    val lower = line?.lowercase() ?: ""
                    if (lower.startsWith("content-length:")) {
                        contentLength = lower.substringAfter("content-length:").trim().toIntOrNull() ?: 0
                    }
                }

                val out = s.getOutputStream()

                if (reqLine.startsWith("POST /upload_cert") || reqLine.startsWith("POST /cert")) {
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = reader.read(bodyChars, readTotal, contentLength - readTotal)
                        if (r < 0) break
                        readTotal += r
                    }
                    val body = String(bodyChars, 0, readTotal)
                    val dir = certDirProvider?.invoke()
                    if (dir != null && body.isNotBlank()) {
                        val domain = if (reqLine.contains("domain=")) {
                            reqLine.substringAfter("domain=").substringBefore("&").substringBefore(" ").trim()
                        } else {
                            "absenku.duckdns.org"
                        }
                        val isKey = reqLine.contains("type=key")
                        val file = if (isKey) File(dir, "$domain.key") else File(dir, "$domain.crt")
                        file.writeText(body)
                        CaddyService.emitLog("LogServer: Received cert upload for ${file.name} (${file.length()} bytes)")
                        val msg = "OK, saved to ${file.name} (${file.length()} bytes)\r\n"
                        val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${msg.length}\r\nConnection: close\r\n\r\n$msg"
                        out.write(resp.toByteArray(Charsets.US_ASCII))
                    } else {
                        val err = "Error: cert dir not available or empty body\r\n"
                        out.write("HTTP/1.1 500 Internal Error\r\nContent-Length: ${err.length}\r\nConnection: close\r\n\r\n$err".toByteArray(Charsets.US_ASCII))
                    }
                    out.flush()
                    return
                }

                val logs = logProvider()
                val bodyBytes = logs.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${bodyBytes.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.US_ASCII))
                out.write(bodyBytes)
                out.flush()
            }
        } catch (e: Exception) {
            // Ignore disconnects
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
    }
}

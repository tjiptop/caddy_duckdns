package com.caddy.proxy

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

object LogServer {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    const val PORT = 8088

    fun start(logProvider: () -> String) {
        if (isRunning) return
        isRunning = true

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
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val reqLine = reader.readLine() ?: return
                // Drain HTTP request headers
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) break
                }

                val logs = logProvider()
                val bodyBytes = logs.toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
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

package com.caddy.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object DuckDnsHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Updates DuckDNS domain record to point to target IP.
     * @param fullDomain e.g. "tjipto.duckdns.org" or "tjipto"
     * @param token DuckDNS token
     * @param ip Target IP address (e.g. 192.168.43.1)
     */
    suspend fun updateIp(fullDomain: String, token: String, ip: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val subdomain = fullDomain.trim()
                .removeSuffix(".duckdns.org")
                .removeSuffix(".")

            val url = "https://www.duckdns.org/update?domains=$subdomain&token=$token&ip=$ip"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CaddyProxy-Android/1.0")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()?.trim() ?: ""
                    if (response.isSuccessful && body.contains("OK")) {
                        Pair(true, "DuckDNS update OK ($subdomain -> $ip)")
                    } else {
                        Pair(false, "DuckDNS response: $body (HTTP ${response.code})")
                    }
                }
            } catch (e: Exception) {
                Pair(false, "DuckDNS update error: ${e.message}")
            }
        }
}

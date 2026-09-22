package com.caddy.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object DuckDnsHelper {

    private const val ISRG_ROOT_X1_CERT = """-----BEGIN CERTIFICATE-----
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
-----END CERTIFICATE-----"""

    private val client: OkHttpClient by lazy {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val isrgCert = cf.generateCertificate(ByteArrayInputStream(ISRG_ROOT_X1_CERT.toByteArray())) as X509Certificate

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg_root_x1", isrgCert)
            }

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }

            val trustManagers = tmf.trustManagers
            val x509TrustManager = trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager

            if (x509TrustManager != null) {
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(x509TrustManager), null)
                }
                OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, x509TrustManager)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
            } else {
                buildDefaultClient()
            }
        } catch (e: Exception) {
            buildDefaultClient()
        }
    }

    private fun buildDefaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Updates DuckDNS domain record to point to target IP.
     * Tries HTTPS first; falls back to HTTP automatically if Android 5 SSL handshake fails.
     * @param fullDomain e.g. "tjipto.duckdns.org" or "tjipto"
     * @param token DuckDNS token
     * @param ip Target IP address (e.g. 192.168.2.230)
     */
    suspend fun updateIp(fullDomain: String, token: String, ip: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val subdomain = fullDomain.trim()
                .removeSuffix(".duckdns.org")
                .removeSuffix(".")

            val queryParams = "domains=$subdomain&token=$token&ip=$ip"

            // 1. First attempt: HTTPS
            val httpsUrl = "https://www.duckdns.org/update?$queryParams"
            try {
                val req = Request.Builder()
                    .url(httpsUrl)
                    .header("User-Agent", "CaddyProxy-Android/1.1")
                    .build()

                client.newCall(req).execute().use { response ->
                    val body = response.body?.string()?.trim() ?: ""
                    if (response.isSuccessful && body.contains("OK")) {
                        return@withContext Pair(true, "DuckDNS update OK via HTTPS ($subdomain -> $ip)")
                    }
                }
            } catch (httpsEx: Exception) {
                // Ignore and proceed to HTTP fallback
            }

            // 2. Fallback attempt: HTTP (Safe for legacy Android 5 / expired TLS stores)
            val httpUrl = "http://www.duckdns.org/update?$queryParams"
            try {
                val req = Request.Builder()
                    .url(httpUrl)
                    .header("User-Agent", "CaddyProxy-Android/1.1")
                    .build()

                client.newCall(req).execute().use { response ->
                    val body = response.body?.string()?.trim() ?: ""
                    if (response.isSuccessful && body.contains("OK")) {
                        return@withContext Pair(true, "DuckDNS update OK via HTTP fallback ($subdomain -> $ip)")
                    } else {
                        return@withContext Pair(false, "DuckDNS response: $body (HTTP ${response.code})")
                    }
                }
            } catch (httpEx: Exception) {
                return@withContext Pair(false, "DuckDNS update failed: ${httpEx.message}")
            }
        }
}


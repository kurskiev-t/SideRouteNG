package com.kurskievtr.siderouteng

import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Downloads public SOCKS5 lists and keeps the endpoints that can actually reach YouTube. */
class ProxyFinder(listener: Listener) : Finder(listener) {
    override val concurrency: Int = 64

    override fun collect(): List<Candidate> {
        val endpoints = LinkedHashSet<ProxyEndpoint>()
        for (source in SOURCES) {
            val text = download(source)
            if (text == null) {
                AppLog.w("proxy source failed: $source")
                continue
            }
            val found = ADDRESS.findAll(text).mapNotNull { ProxyEndpoint.parse(it.value) }.toList()
            endpoints.addAll(found)
            AppLog.i("proxy source ${found.size} candidates: $source")
        }
        return endpoints.map { Candidate(it.toString(), it.toString()) }
    }

    override fun measure(candidate: Candidate): Long? {
        val endpoint = ProxyEndpoint.parse(candidate.value) ?: return null
        val startedAt = SystemClock.elapsedRealtime()
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved(endpoint.host, endpoint.port)
        )
        var connection: HttpURLConnection? = null
        try {
            connection = URL(CHECK_URL).openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = CHECK_TIMEOUT_MS
            connection.readTimeout = CHECK_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code >= 500) return null
            return SystemClock.elapsedRealtime() - startedAt
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        /**
         * SOCKS5 lists that still produced working proxies in practice; the CDN mirrors answer
         * faster and more reliably than raw.githubusercontent.
         */
        private val SOURCES = listOf(
            "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt",
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/protocols/socks5/data.txt",
            "https://cdn.jsdelivr.net/gh/databay-labs/free-proxy-list/socks5.txt",
            "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies" +
                "&proxy_format=protocolipport&format=text&protocol=socks5&timeout=5000"
        )
        private val ADDRESS = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}:\d{2,5}\b""")
        private const val CHECK_TIMEOUT_MS = 5_000
    }
}

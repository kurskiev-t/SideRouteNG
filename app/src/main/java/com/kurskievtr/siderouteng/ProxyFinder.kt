package com.kurskievtr.siderouteng

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A candidate that answered the check, with the time its answer took. */
data class ProxyProbeResult(val endpoint: ProxyEndpoint, val latencyMs: Long)

/**
 * Downloads public SOCKS5 lists and keeps the candidates that can actually reach YouTube.
 *
 * The lists are mostly dead — a few thousand candidates yield single-digit working proxies — so
 * every candidate is checked with a real request and the survivors are reported as they appear
 * instead of after the whole run.
 */
class ProxyFinder(private val listener: Listener) {
    interface Listener {
        fun onProgress(checked: Int, total: Int, live: Int)
        fun onFound(result: ProxyProbeResult)
        fun onFinished(checked: Int, live: Int, cancelled: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val checked = AtomicInteger(0)
    private val live = AtomicInteger(0)
    private var workers: ExecutorService? = null
    private var driver: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        checked.set(0)
        live.set(0)
        val thread = Thread({ run() }, "proxy-finder")
        driver = thread
        thread.start()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        workers?.shutdownNow()
        driver?.interrupt()
    }

    private fun run() {
        val candidates = LinkedHashSet<ProxyEndpoint>()
        for (source in SOURCES) {
            if (!running.get()) break
            val text = download(source)
            if (text == null) {
                AppLog.w("proxy source failed: $source")
                continue
            }
            val found = ADDRESS.findAll(text).mapNotNull { ProxyEndpoint.parse(it.value) }.toList()
            candidates.addAll(found)
            AppLog.i("proxy source ${found.size} candidates: $source")
        }
        val total = candidates.size
        AppLog.i("proxy finder: checking $total candidates")
        publishProgress(total)

        val pool = ThreadPoolExecutor(
            CONCURRENCY,
            CONCURRENCY,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(maxOf(total, 1)),
            Executors.defaultThreadFactory(),
            ThreadPoolExecutor.CallerRunsPolicy()
        )
        workers = pool
        candidates.forEach { candidate ->
            pool.execute {
                if (running.get()) check(candidate, total)
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val cancelled = !running.getAndSet(false)
        workers = null
        driver = null
        val done = checked.get()
        val alive = live.get()
        AppLog.i("proxy finder finished: $alive live of $done checked")
        main.post { listener.onFinished(done, alive, cancelled) }
    }

    private fun check(endpoint: ProxyEndpoint, total: Int) {
        val latency = measure(endpoint)
        checked.incrementAndGet()
        if (latency != null) {
            live.incrementAndGet()
            main.post { listener.onFound(ProxyProbeResult(endpoint, latency)) }
        }
        publishProgress(total)
    }

    /** Returns how long a real request through [endpoint] took, or null when it did not work. */
    private fun measure(endpoint: ProxyEndpoint): Long? {
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

    private fun publishProgress(total: Int) {
        val done = checked.get()
        val alive = live.get()
        main.post { listener.onProgress(done, total, alive) }
    }

    private fun download(url: String): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = SOURCE_TIMEOUT_MS
            connection.readTimeout = SOURCE_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
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
        private const val CHECK_URL = "https://www.youtube.com/generate_204"
        private const val CHECK_TIMEOUT_MS = 5_000
        private const val SOURCE_TIMEOUT_MS = 20_000
        private const val CONCURRENCY = 64
        private const val RUN_TIMEOUT_MS = 30 * 60_000L
        private const val USER_AGENT = "SideRouteNG/1.0"
    }
}

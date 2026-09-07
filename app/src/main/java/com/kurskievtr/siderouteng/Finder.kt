package com.kurskievtr.siderouteng

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Something the finder can check: [label] is shown in the table, [value] goes into the settings. */
data class Candidate(val label: String, val value: String)

/** A candidate that answered the check, with the time its answer took. */
data class FinderResult(val candidate: Candidate, val latencyMs: Long)

/**
 * Collects upstream candidates from public lists and keeps the ones that can reach the check URL.
 *
 * The lists are mostly dead, so every candidate is checked with a real request and the survivors
 * are reported as they appear instead of after the whole run.
 */
abstract class Finder(private val listener: Listener) {
    interface Listener {
        fun onProgress(checked: Int, total: Int, live: Int)
        fun onFound(result: FinderResult)
        fun onFinished(checked: Int, live: Int, cancelled: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val checked = AtomicInteger(0)
    private val live = AtomicInteger(0)
    private var workers: ExecutorService? = null
    private var driver: Thread? = null

    /** Candidates to check, already deduplicated and capped. */
    protected abstract fun collect(): List<Candidate>

    /** How long a real request through [candidate] took, or null when it did not work. */
    protected abstract fun measure(candidate: Candidate): Long?

    /** How many candidates are checked at a time. */
    protected abstract val concurrency: Int

    /** The run stops once this many candidates have answered. */
    protected open val enough: Int = Int.MAX_VALUE

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        checked.set(0)
        live.set(0)
        val thread = Thread({ run() }, "finder")
        driver = thread
        thread.start()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        workers?.shutdownNow()
        driver?.interrupt()
    }

    private fun run() {
        val candidates = collect()
        val total = candidates.size
        AppLog.i("finder: checking $total candidates")
        publishProgress(total)

        val pool = ThreadPoolExecutor(
            concurrency,
            concurrency,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(maxOf(total, 1)),
            Executors.defaultThreadFactory(),
            ThreadPoolExecutor.CallerRunsPolicy()
        )
        workers = pool
        candidates.forEach { candidate ->
            pool.execute {
                if (running.get() && live.get() < enough) check(candidate, total)
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
        AppLog.i("finder finished: $alive live of $done checked")
        main.post { listener.onFinished(done, alive, cancelled) }
    }

    private fun check(candidate: Candidate, total: Int) {
        val latency = measure(candidate)
        checked.incrementAndGet()
        if (latency != null) {
            live.incrementAndGet()
            main.post { listener.onFound(FinderResult(candidate, latency)) }
        }
        publishProgress(total)
    }

    private fun publishProgress(total: Int) {
        val done = checked.get()
        val alive = live.get()
        main.post { listener.onProgress(done, total, alive) }
    }

    protected fun download(url: String): String? {
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
        /** A request that answers quickly and only when YouTube itself is reachable. */
        const val CHECK_URL = "https://www.youtube.com/generate_204"
        private const val SOURCE_TIMEOUT_MS = 20_000
        private const val RUN_TIMEOUT_MS = 30 * 60_000L
        private const val USER_AGENT = "SideRouteNG/1.0"
    }
}

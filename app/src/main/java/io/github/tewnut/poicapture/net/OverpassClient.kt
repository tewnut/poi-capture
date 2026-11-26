package io.github.tewnut.poicapture.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.tewnut.poicapture.core.Overpass
import io.github.tewnut.poicapture.core.Way
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Overpass over HTTP, shared by the setup screen and the capture service (one process-wide cache, so cells loaded
 * on the setup screen are not loaded again when recording starts). Rate-limited, and falling back to public
 * mirrors when the configured server is overloaded (502/504/timeouts are common on the main instance).
 */
object OverpassClient {
    val MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    private val cache = HashMap<String, List<Way>>()
    private val lock = Mutex()
    private var last = 0L

    fun cached(q: String): List<Way>? = synchronized(cache) { cache[q] }

    suspend fun ways(url: String, q: String): List<Way> {
        cached(q)?.let { return it }
        val ways = Overpass.parseWays(body(url, q))
        synchronized(cache) { cache[q] = ways }
        return ways
    }

    private val bodies = HashMap<String, String>()

    /** One try on one server, for the logs and indoor.jsonl's `lookup` lines. */
    data class Attempt(val mirror: String, val http: Int?, val ms: Long, val bytes: Int, val error: String?)

    /**
     * The raw JSON answer to any query (indoor mode: buildings, shops inside), same mirrors and rate limit, cached.
     * Every try is logged (tag "QuetPho", [label] says which query) and added to [trace]. The lock only spaces the
     * requests' starts (1.5 s apart): a slow answer for one query (a road cell) no longer holds back another (the
     * building lookup). An answer that is a runtime error (timeout, out of memory) counts as a failure: next server.
     */
    suspend fun body(url: String, q: String, label: String = "query", trace: MutableList<Attempt>? = null,
                     readTimeoutMs: Int = 30_000): String {
        synchronized(bodies) { bodies[q] }?.let {
            android.util.Log.i(TAG, "overpass $label: cached answer (${it.length / 1024} kB)")
            trace?.add(Attempt("cache", 200, 0, it.length, null))
            return it
        }
        var lastError: Exception? = null
        for (u in (listOf(url) + MIRRORS).distinct()) {
            lock.withLock {
                val wait = 1500 - (System.currentTimeMillis() - last)
                if (wait > 0) delay(wait)
                last = System.currentTimeMillis()
            }
            val host = runCatching { URL(u).host }.getOrDefault(u)
            val t0 = System.currentTimeMillis()
            var code: Int? = null
            try {
                val (c, b) = withContext(Dispatchers.IO) { post(u, q, readTimeoutMs) }
                code = c
                if (c != 200) error("HTTP $c")
                val json = org.json.JSONObject(b)   // a busy server may answer 200 with an HTML page: try the next one
                val remark = json.optString("remark")
                if (remark.contains("runtime error", ignoreCase = true)) error("remark: ${remark.take(120)}")
                val ms = System.currentTimeMillis() - t0
                android.util.Log.i(TAG, "overpass $label: $host HTTP $c in $ms ms (${b.length / 1024} kB)")
                trace?.add(Attempt(host, c, ms, b.length, null))
                // way lists are kept parsed (smaller); other answers as text
                if (!q.contains("[highway]")) synchronized(bodies) { bodies[q] = b }
                return b
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                val why = e.message ?: e.javaClass.simpleName
                android.util.Log.i(TAG, "overpass $label: $host failed in $ms ms: $why")
                trace?.add(Attempt(host, code, ms, 0, why.take(160)))
                lastError = e
            }
        }
        throw RoadDataUnavailable(lastError)
    }

    private const val TAG = "QuetPho"

    /** (HTTP status, body); the body of an error answer is not read. */
    private fun post(url: String, q: String, readTimeoutMs: Int): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("User-Agent", "poi-capture-android/0.8")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            val code = c.responseCode
            if (code != 200) return code to ""
            return code to c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }
}

/** No road data from any Overpass server (overloaded or offline); the UI says so in the user's language. */
class RoadDataUnavailable(cause: Throwable?) :
    IllegalStateException("No road data (OpenStreetMap server busy or offline)" + (cause?.message?.let { " [$it]" } ?: ""), cause)

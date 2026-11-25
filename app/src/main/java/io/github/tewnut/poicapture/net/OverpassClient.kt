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

    /** The raw JSON answer to any query (indoor mode: buildings, shops inside), same mirrors and rate limit, cached. */
    suspend fun body(url: String, q: String): String = lock.withLock {
        synchronized(bodies) { bodies[q] }?.let { return it }
        var lastError: Exception? = null
        for (u in (listOf(url) + MIRRORS).distinct()) {
            val wait = 1500 - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
            try {
                val b = withContext(Dispatchers.IO) { post(u, q) }
                org.json.JSONObject(b)   // a busy server may answer 200 with an HTML page: try the next one
                // way lists are kept parsed (smaller); other answers as text
                if (!q.contains("[highway]")) synchronized(bodies) { bodies[q] = b }
                return b
            } catch (e: Exception) { lastError = e }
        }
        throw RoadDataUnavailable(lastError)
    }

    private fun post(url: String, q: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("User-Agent", "poi-capture-android/0.8")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }
}

/** No road data from any Overpass server (overloaded or offline); the UI says so in the user's language. */
class RoadDataUnavailable(cause: Throwable?) :
    IllegalStateException("No road data (OpenStreetMap server busy or offline)" + (cause?.message?.let { " [$it]" } ?: ""), cause)

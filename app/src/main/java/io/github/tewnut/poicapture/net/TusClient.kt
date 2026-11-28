package io.github.tewnut.poicapture.net

import android.os.Build
import io.github.tewnut.poicapture.BuildConfig
import io.github.tewnut.poicapture.core.CloudMe
import io.github.tewnut.poicapture.core.DirectUpload
import io.github.tewnut.poicapture.core.Tus
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's client for the project's server (server/README.md) on HttpURLConnection (the app's only HTTP stack):
 * who the token is (GET /me), direct uploads (POST /direct…, the parts PUT straight to R2), and an upload's state at
 * the tus endpoint (HEAD, DELETE). The token goes in the Authorization header only; it is never logged or put in an
 * exception message.
 */
class TusClient(endpoint: String, token: String?) {
    val endpoint: String = Tus.endpoint(endpoint) ?: throw IllegalArgumentException("not an http(s) URL")
    private val auth = Tus.authHeader(token)

    /** A non-2xx answer; [code] tells the worker whether trying again can help. */
    class HttpError(val code: Int, what: String) : IOException("$what: HTTP $code") {
        /** 401 / 403: the token; 413: too large for the server; 400 / 405 / 501: the server does not do this. */
        val permanent get() = code in setOf(400, 401, 403, 405, 413, 501)
    }

    companion object {
        /** `X-Client` on every request: the app version and the device, so the server's "last seen" can tell phones apart. */
        val CLIENT: String by lazy { Tus.clientHeader(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL) }
    }

    /** Connections open now, so [abortAll] can cut them (blocking socket I/O does not notice coroutine cancellation). */
    private val live: MutableSet<HttpURLConnection> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Disconnect every request in flight: they fail at once with an IOException. */
    fun abortAll() { live.toList().forEach { runCatching { it.disconnect() } } }

    private fun close(c: HttpURLConnection) { live.remove(c); c.disconnect() }

    private fun open(url: String, method: String, timeoutMs: Int = 30_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).also { live.add(it) }.apply {
            connectTimeout = 15_000
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            useCaches = false
            requestMethod = method
            setRequestProperty("Tus-Resumable", Tus.VERSION)
            auth?.let { setRequestProperty("Authorization", it) }
            setRequestProperty("X-Client", CLIENT)
        }

    /**
     * `GET <origin>/me`: who the token belongs to (server/README.md). null when the server has no /me (a plain tus
     * server: 404 and the like) or answers something else; 401 / 403 throw [HttpError] (bad or revoked token).
     */
    fun me(): CloudMe? {
        val url = CloudMe.url(endpoint) ?: return null
        val c = open(url, "GET", timeoutMs = 15_000)
        try {
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code == 401 || code == 403) throw HttpError(code, "me")
            if (code !in 200..299) return null
            val body = c.inputStream.use { input ->            // small JSON; read at most 16 KB of anything else
                val buf = java.io.ByteArrayOutputStream()
                val b = ByteArray(4096)
                while (buf.size() < 16 * 1024) { val n = input.read(b); if (n < 0) break; buf.write(b, 0, n) }
                buf.toString("UTF-8")
            }
            return CloudMe.parse(body)
        } finally { close(c) }
    }

    // ---- direct uploads (server/README.md "Direct uploads"): JSON to the Worker, the bytes straight to R2

    private fun postJson(path: String, body: String, what: String): String? {
        val url = DirectUpload.url(endpoint, path) ?: return null
        val c = open(url, "POST", timeoutMs = 30_000)
        try {
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            val b = body.toByteArray(Charsets.UTF_8)
            c.doOutput = true
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw HttpError(code, what)
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { close(c) }
    }

    /** Opens a direct upload: the upload and its first part URLs. 501: the server has no R2 keys set. */
    fun directCreate(size: Long, metadata: Map<String, String?>): Pair<DirectUpload, Map<Int, String>> =
        DirectUpload.parseCreate(postJson("", DirectUpload.createBody(size, metadata), "direct") ?: throw HttpError(501, "direct"))

    /** Fresh presigned URLs for [parts] (they last 6 h). 404: the server forgot the upload. */
    fun directUrls(id: String, parts: List<Int>): Map<Int, String> =
        DirectUpload.parseUrls(postJson("/$id/urls", DirectUpload.urlsBody(parts), "direct urls") ?: throw HttpError(404, "direct urls"))

    /** Every part is in R2: the server joins them. 409: R2 does not have them all (start again). */
    fun directComplete(d: DirectUpload) {
        postJson("/${d.id}/complete", d.completeBody(), "direct complete") ?: throw HttpError(404, "direct complete")
    }

    /**
     * PUT one part to its presigned R2 [url] (no Authorization, no tus headers: the URL is the permission); returns R2's
     * ETag. 403: the URL expired (ask for a new one).
     */
    fun putPart(url: String, file: File, fileOffset: Long, length: Long, timeoutMs: Int, onSent: (Long) -> Unit): String {
        val c = (URL(url).openConnection() as HttpURLConnection).also { live.add(it) }
        try {
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setFixedLengthStreamingMode(length)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fileOffset)
                c.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var left = length
                    var sent = 0L
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("the zip got shorter while uploading")
                        out.write(buf, 0, n)
                        left -= n
                        sent += n
                        onSent(sent)
                    }
                }
            }
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code, "part")
            return c.getHeaderField("ETag")?.takeIf { it.isNotBlank() } ?: throw IOException("part: no ETag from R2")
        } finally { close(c) }
    }

    /** HEAD: the server's offset for this upload; null when the server does not know it (any more). */
    fun offset(url: String): Long? {
        val c = open(url, "HEAD")
        try {
            val code = c.responseCode
            if (code == 404 || code == 410) return null
            if (code !in 200..299) throw HttpError(code, "HEAD")
            return c.getHeaderField("Upload-Offset")?.toLongOrNull() ?: throw IOException("HEAD: no Upload-Offset")
        } finally { close(c) }
    }

    /** tus termination of an unfinished upload of ours (best effort: false when the server said no). */
    fun delete(url: String): Boolean {
        val c = open(url, "DELETE")
        try { return c.responseCode in 200..299 } finally { close(c) }
    }
}

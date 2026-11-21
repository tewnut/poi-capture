package io.github.tewnut.poicapture.net

import android.os.Build
import io.github.tewnut.poicapture.BuildConfig
import io.github.tewnut.poicapture.core.CloudMe
import io.github.tewnut.poicapture.core.Tus
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * A small tus 1.0 client on HttpURLConnection (the app's only HTTP stack): OPTIONS, creation (POST), HEAD and PATCH.
 * HttpURLConnection has no PATCH, so PATCH goes as POST with `X-HTTP-Method-Override: PATCH` (tusd and server/ accept
 * it). The token goes in the Authorization header only; it is never logged or put in an exception message.
 */
class TusClient(endpoint: String, token: String?) {
    val endpoint: String = Tus.endpoint(endpoint) ?: throw IllegalArgumentException("not an http(s) URL")
    private val auth = Tus.authHeader(token)

    /** A non-2xx answer; [code] tells the worker whether trying again can help. */
    class HttpError(val code: Int, what: String) : IOException("$what: HTTP $code") {
        /** 401 / 403: the token; 413: too large for the server; 400 / 412: the server does not speak this. */
        val permanent get() = code in setOf(400, 401, 403, 412, 413)
    }

    companion object {
        /** `X-Client` on every request: the app version and the device, so the server's "last seen" can tell phones apart. */
        val CLIENT: String by lazy { Tus.clientHeader(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL) }
    }

    private fun open(url: String, method: String, timeoutMs: Int = 30_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            useCaches = false
            if (method == "PATCH") {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else requestMethod = method
            setRequestProperty("Tus-Resumable", Tus.VERSION)
            auth?.let { setRequestProperty("Authorization", it) }
            setRequestProperty("X-Client", CLIENT)
        }

    /** OPTIONS on the endpoint: what the server supports (the test in Settings). Checks the token too (server/). */
    fun options(): Tus.ServerInfo {
        val c = open(endpoint, "OPTIONS")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code, "OPTIONS")
            return Tus.serverInfo(c.getHeaderField("Tus-Version"), c.getHeaderField("Tus-Extension"), c.getHeaderField("Tus-Max-Size"))
        } finally { c.disconnect() }
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
        } finally { c.disconnect() }
    }

    /** POST with Upload-Length and Upload-Metadata; returns the new upload's URL. */
    fun create(size: Long, metadata: Map<String, String?>): String {
        val c = open(endpoint, "POST")
        try {
            c.setRequestProperty("Upload-Length", size.toString())
            c.setRequestProperty("Upload-Metadata", Tus.encodeMetadata(metadata))
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
            val code = c.responseCode
            if (code != 201) throw HttpError(code, "create")
            val loc = c.getHeaderField("Location") ?: throw IOException("create: no Location from the server")
            return Tus.resolve(endpoint, loc)
        } finally { c.disconnect() }
    }

    /** HEAD: the server's offset for this upload; null when the server does not know it (any more). */
    fun offset(url: String): Long? {
        val c = open(url, "HEAD")
        try {
            val code = c.responseCode
            if (code == 404 || code == 410) return null
            if (code !in 200..299) throw HttpError(code, "HEAD")
            return c.getHeaderField("Upload-Offset")?.toLongOrNull() ?: throw IOException("HEAD: no Upload-Offset")
        } finally { c.disconnect() }
    }

    /**
     * PATCH [length] bytes of [file] from [offset]; returns the server's new offset. [onSent] gets the bytes sent so
     * far in this request (for progress). 409: the offsets disagree, the caller asks again with HEAD.
     */
    fun patch(url: String, file: File, offset: Long, length: Long, onSent: (Long) -> Unit = {}): Long {
        val c = open(url, "PATCH", timeoutMs = 120_000)
        try {
            c.setRequestProperty("Upload-Offset", offset.toString())
            c.setRequestProperty("Content-Type", "application/offset+octet-stream")
            c.doOutput = true
            c.setFixedLengthStreamingMode(length)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
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
            if (code !in 200..299) throw HttpError(code, "PATCH")
            return c.getHeaderField("Upload-Offset")?.toLongOrNull() ?: throw IOException("PATCH: no Upload-Offset")
        } finally { c.disconnect() }
    }
}

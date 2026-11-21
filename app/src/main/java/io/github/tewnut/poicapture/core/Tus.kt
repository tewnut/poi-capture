package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Base64

/**
 * The pure parts of uploading a session to a tus server (tus.io resumable upload protocol 1.0): metadata encoding,
 * endpoint and Location handling, and where to (re)start an upload. The HTTP side is net/TusClient.kt, the background
 * work capture/UploadWork.kt.
 */
object Tus {
    const val VERSION = "1.0.0"
    /** Files up to this size go in one upload; bigger ones in parallel partials when the server has `concatenation`. */
    const val PARALLEL_ABOVE = 16L * 1024 * 1024
    /** Partials start at multiples of this. */
    const val PART_ALIGN = 8L * 1024 * 1024
    /** At most this many partials (= connections) per file. */
    const val MAX_PARTS = 4

    /**
     * The `X-Client` header (server/README.md, "Last seen"): `POI Capture/<version> (Android <release>; <manufacturer>
     * <model>)`, printable ASCII only (anything else becomes '?', blanks collapse), at most 120 characters.
     */
    fun clientHeader(versionName: String, androidRelease: String?, manufacturer: String?, model: String?): String {
        fun clean(s: String?) = (s ?: "").map { if (it in ' '..'~') it else '?' }.joinToString("")
            .replace(Regex("\\s+"), " ").trim()
        val device = listOf(clean(manufacturer), clean(model)).filter { it.isNotEmpty() }.joinToString(" ")
        val h = "POI Capture/${clean(versionName)} (Android ${clean(androidRelease)}; $device)"
        return h.take(120).trim()
    }

    /** Upload-Metadata: comma-separated "key base64(value)"; an empty value is sent as the bare key, null is left out. */
    fun encodeMetadata(meta: Map<String, String?>): String = meta.entries.mapNotNull { (k, v) ->
        require(k.isNotEmpty() && k.none { it == ' ' || it == ',' }) { "bad metadata key '$k'" }
        when {
            v == null -> null
            v.isEmpty() -> k
            else -> k + " " + Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8))
        }
    }.joinToString(",")

    fun decodeMetadata(header: String?): Map<String, String> = (header ?: "").split(',').mapNotNull { pair ->
        val p = pair.trim()
        if (p.isEmpty()) return@mapNotNull null
        val i = p.indexOf(' ')
        if (i < 0) p to "" else p.substring(0, i) to String(Base64.getDecoder().decode(p.substring(i + 1).trim()), Charsets.UTF_8)
    }.toMap()

    /** The endpoint as typed, trimmed, with one trailing slash; null when it is not an http(s) URL with a host. */
    fun endpoint(typed: String): String? {
        val t = typed.trim()
        if (t.isEmpty()) return null
        val u = runCatching { URI(t) }.getOrNull() ?: return null
        if (u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrBlank()) return null
        return t.trimEnd('/') + "/"
    }

    /** A Location header (absolute, or relative as some servers send) resolved against the endpoint. */
    fun resolve(endpoint: String, location: String): String = URI(endpoint).resolve(location.trim()).toString()

    /** "Bearer x" for a token typed with or without the word Bearer; null for none. */
    fun authHeader(token: String?): String? {
        val t = token?.trim()?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
        return if (t.isNullOrEmpty()) null else "Bearer $t"
    }

    /**
     * The contiguous byte ranges a file of [size] is sent in (server/README.md, "Parallel uploads"): one range up to
     * [PARALLEL_ABOVE]; above, at most [maxParts] ranges of equal length rounded up to a multiple of [align] (the last
     * one shorter), so every cut is at a multiple of [align].
     */
    fun splitRanges(size: Long, maxParts: Int = MAX_PARTS, align: Long = PART_ALIGN, parallelAbove: Long = PARALLEL_ABOVE): List<UploadPart> {
        require(size >= 0 && maxParts >= 1 && align > 0)
        if (size <= parallelAbove || maxParts == 1) return listOf(UploadPart(0, size))
        val blocks = (size + align - 1) / align
        val perPart = (blocks + maxParts - 1) / maxParts * align
        return generateSequence(0L) { it + perPart }.takeWhile { it < size }
            .map { UploadPart(it, minOf(perPart, size - it)) }.toList()
    }

    /** Saved partials still describe a file of [size]: contiguous from 0, covering it exactly, offsets in range. */
    fun partsFit(parts: List<UploadPart>, size: Long): Boolean {
        if (parts.isEmpty()) return false
        var at = 0L
        for (p in parts) {
            if (p.start != at || p.length <= 0 || p.offset < 0 || p.offset > p.length) return false
            at += p.length
        }
        return at == size
    }

    /** `Upload-Concat` of the final upload: the partials' URLs in file order. */
    fun finalConcat(urls: List<String>): String {
        require(urls.isNotEmpty() && urls.none { it.isBlank() || it.contains(' ') })
        return "final;" + urls.joinToString(" ")
    }

    /**
     * An unfinished single upload (from before parallel uploads, or to a server without them) that could now go in
     * parallel: keep it when at least half is on the server already, else start again in parts (faster overall).
     */
    fun keepSingle(serverOffset: Long, size: Long): Boolean = serverOffset * 2 >= size

    /** Where an upload (re)starts. */
    sealed class Start {
        /** Build the zip if needed and create a new upload. */
        data object Create : Start()
        data class Continue(val url: String, val offset: Long) : Start()
        data class Finished(val url: String) : Start()
    }

    /**
     * [saved]: the upload URL and zip size kept from an earlier attempt (null: none). [zipSize]: the zip on the phone
     * now (null: gone). [serverOffset]: the server's Upload-Offset from a HEAD on the saved URL (null: unknown upload,
     * 404 / 410). A new upload whenever the bytes could differ from what the server holds.
     */
    fun start(saved: UploadRecord?, zipSize: Long?, serverOffset: Long?): Start {
        val url = saved?.url ?: return Start.Create
        if (zipSize == null || zipSize != saved.size || serverOffset == null) return Start.Create
        if (serverOffset < 0 || serverOffset > zipSize) return Start.Create
        return if (serverOffset == zipSize) Start.Finished(url) else Start.Continue(url, serverOffset)
    }

    /** What OPTIONS on the endpoint said. */
    data class ServerInfo(val versions: List<String>, val extensions: List<String>, val maxSize: Long?) {
        val supports100 get() = VERSION in versions
        val canCreate get() = "creation" in extensions
        val canConcat get() = "concatenation" in extensions
    }

    fun serverInfo(tusVersion: String?, tusExtension: String?, tusMaxSize: String?) = ServerInfo(
        tusVersion.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        tusExtension.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        tusMaxSize?.trim()?.toLongOrNull())
}

enum class UploadStatus(val json: String) {
    NONE("none"), QUEUED("queued"), UPLOADING("uploading"), DONE("done"), FAILED("failed");
    companion object { fun of(s: String?) = entries.firstOrNull { it.json == s } ?: NONE }
}

/**
 * One partial upload of a file sent in parallel: bytes [start] until start + [length] of the zip, the partial's [url]
 * (null: not created yet) and how much of it the server has ([offset], relative to [start]).
 */
data class UploadPart(val start: Long, val length: Long, val url: String? = null, val offset: Long = 0) {
    val done get() = url != null && offset >= length

    fun toJson(): JSONObject = JSONObject().put("start", start).put("length", length).put("url", url.orNull()).put("offset", offset)

    companion object {
        fun fromJson(o: JSONObject) = UploadPart(o.optLong("start"), o.optLong("length"), o.optStr("url"), o.optLong("offset"))
    }
}

/**
 * What the phone remembers about one session's upload (kept across restarts, so an upload resumes). A single upload
 * has [url] and [offset]. A parallel one has [parts] (the partials, in file order), [offset] = the sum of theirs, and
 * [url] = the final upload once the server created it.
 */
data class UploadRecord(
    val sessionId: String,
    val status: UploadStatus = UploadStatus.NONE,
    val url: String? = null,
    val size: Long = 0,
    val offset: Long = 0,
    val error: String? = null,
    val uploadedAt: String? = null,
    val parts: List<UploadPart> = emptyList(),
) {
    val percent: Int get() = if (size <= 0) 0 else ((offset * 100) / size).toInt().coerceIn(0, 100)

    /** These partials, with [offset] their sum. */
    fun withParts(p: List<UploadPart>) = copy(parts = p, offset = p.sumOf { it.offset })

    fun toJson(): JSONObject = JSONObject().put("sessionId", sessionId).put("status", status.json).put("url", url.orNull())
        .put("size", size).put("offset", offset).put("error", error.orNull()).put("uploadedAt", uploadedAt.orNull())
        .apply { if (parts.isNotEmpty()) put("parts", JSONArray().apply { parts.forEach { put(it.toJson()) } }) }

    companion object {
        fun fromJson(o: JSONObject) = UploadRecord(o.getString("sessionId"), UploadStatus.of(o.optStr("status")),
            o.optStr("url"), o.optLong("size"), o.optLong("offset"), o.optStr("error"), o.optStr("uploadedAt"),
            o.optJSONArray("parts")?.let { a -> (0 until a.length()).map { UploadPart.fromJson(a.getJSONObject(it)) } } ?: emptyList())
    }
}

/**
 * Upload-Metadata of a raw session (what the agent, `cloud.py list` and the admin map see). With the session's
 * track.jsonl ([track]): `track`, the ride as a Google encoded polyline (≤ 2,000 characters, left out under 2 usable
 * fixes), and `distanceM`; `endedAt` and `photos` (= `photoCount`) per server/README.md "Trip metadata". Nothing private.
 */
fun sessionUploadMetadata(s: Session, fileName: String, photoCount: Int, track: java.io.File? = null): Map<String, String?> = linkedMapOf(
    "filename" to fileName,
    "filetype" to "application/zip",
    "type" to "raw-session",
    "sessionId" to s.id,
    "name" to s.name,
    "kind" to s.kind.json,
    "createdAt" to s.createdAt,
    "startedAt" to s.startedAt,
    "endedAt" to s.endedAt,
    "photoCount" to photoCount.toString(),
    "photos" to photoCount.toString(),
    "distanceM" to track?.takeIf { it.exists() }?.let { Math.round(TrackStats.of(it).distanceM).toString() },
    "track" to track?.let { Polyline.capped(Polyline.trackPoints(it)) },
)

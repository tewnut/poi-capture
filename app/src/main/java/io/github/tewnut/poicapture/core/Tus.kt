package io.github.tewnut.poicapture.core

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
    /** PATCH size: small enough that a dropped mobile connection loses little, big enough to keep requests few. */
    const val CHUNK = 2L * 1024 * 1024

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

    /** Bytes of the next PATCH from [offset] of [size]; 0 when done. */
    fun chunkLength(offset: Long, size: Long, chunk: Long = CHUNK): Long = (size - offset).coerceIn(0, chunk)

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

/** What the phone remembers about one session's upload (kept across restarts, so an upload resumes). */
data class UploadRecord(
    val sessionId: String,
    val status: UploadStatus = UploadStatus.NONE,
    val url: String? = null,
    val size: Long = 0,
    val offset: Long = 0,
    val error: String? = null,
    val uploadedAt: String? = null,
) {
    val percent: Int get() = if (size <= 0) 0 else ((offset * 100) / size).toInt().coerceIn(0, 100)

    fun toJson(): JSONObject = JSONObject().put("sessionId", sessionId).put("status", status.json).put("url", url.orNull())
        .put("size", size).put("offset", offset).put("error", error.orNull()).put("uploadedAt", uploadedAt.orNull())

    companion object {
        fun fromJson(o: JSONObject) = UploadRecord(o.getString("sessionId"), UploadStatus.of(o.optStr("status")),
            o.optStr("url"), o.optLong("size"), o.optLong("offset"), o.optStr("error"), o.optStr("uploadedAt"))
    }
}

/** Upload-Metadata of a raw session (what the agent and `cloud.py list` see). */
fun sessionUploadMetadata(s: Session, fileName: String, photoCount: Int): Map<String, String?> = linkedMapOf(
    "filename" to fileName,
    "filetype" to "application/zip",
    "type" to "raw-session",
    "sessionId" to s.id,
    "name" to s.name,
    "kind" to s.kind.json,
    "createdAt" to s.createdAt,
    "startedAt" to s.startedAt,
    "photoCount" to photoCount.toString(),
)

package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * The pure parts of talking to the project's server: the endpoint (its tus address, where uploads live), the
 * Authorization and X-Client headers, and what the phone keeps about an upload. Uploads themselves go straight to R2
 * (DirectUpload); the HTTP side is net/TusClient.kt, the background work capture/UploadWork.kt.
 */
object Tus {
    const val VERSION = "1.0.0"

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

    /** The endpoint as typed, trimmed, with one trailing slash; null when it is not an http(s) URL with a host. */
    fun endpoint(typed: String): String? {
        val t = typed.trim()
        if (t.isEmpty()) return null
        val u = runCatching { URI(t) }.getOrNull() ?: return null
        if (u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrBlank()) return null
        return t.trimEnd('/') + "/"
    }

    /** "Bearer x" for a token typed with or without the word Bearer; null for none. */
    fun authHeader(token: String?): String? {
        val t = token?.trim()?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
        return if (t.isNullOrEmpty()) null else "Bearer $t"
    }
}

enum class UploadStatus(val json: String) {
    NONE("none"), QUEUED("queued"), UPLOADING("uploading"), DONE("done"), FAILED("failed");
    companion object { fun of(s: String?) = entries.firstOrNull { it.json == s } ?: NONE }
}

/**
 * What the phone remembers about one session's upload (kept across restarts, so an upload resumes): the [direct]
 * upload with the parts R2 has, [offset] = their bytes, and [url] = the upload at the server once complete.
 */
data class UploadRecord(
    val sessionId: String,
    val status: UploadStatus = UploadStatus.NONE,
    val url: String? = null,
    val size: Long = 0,
    val offset: Long = 0,
    val error: String? = null,
    val uploadedAt: String? = null,
    /** A direct upload to R2 (server "Direct uploads"), once the server opened one. */
    val direct: DirectUpload? = null,
) {
    val percent: Int get() = if (size <= 0) 0 else ((offset * 100) / size).toInt().coerceIn(0, 100)

    fun toJson(): JSONObject = JSONObject().put("sessionId", sessionId).put("status", status.json).put("url", url.orNull())
        .put("size", size).put("offset", offset).put("error", error.orNull()).put("uploadedAt", uploadedAt.orNull())
        .apply { direct?.let { put("direct", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject) = UploadRecord(o.getString("sessionId"), UploadStatus.of(o.optStr("status")),
            o.optStr("url"), o.optLong("size"), o.optLong("offset"), o.optStr("error"), o.optStr("uploadedAt"),
            o.optJSONObject("direct")?.let { DirectUpload.fromJson(it) })
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
    "target" to (if (s.kind == SessionKind.SWEEP) s.target.json else null),
    "createdAt" to s.createdAt,
    "startedAt" to s.startedAt,
    "endedAt" to s.endedAt,
    "photoCount" to photoCount.toString(),
    "photos" to photoCount.toString(),
    "distanceM" to track?.takeIf { it.exists() }?.let { Math.round(TrackStats.of(it).distanceM).toString() },
    "track" to track?.let { Polyline.capped(Polyline.trackPoints(it)) },
)

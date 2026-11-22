package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * A direct upload (server/README.md, "Direct uploads"): the server opens an R2 multipart upload and hands out presigned
 * URLs, one per [partSize] part; the phone PUTs the parts straight to R2 (several at once, any order), keeps each
 * part's ETag, and asks the server to complete. Much faster than tus through the Worker from Vietnam (2026-11-22:
 * 1.1–1.5 MB/s per connection, 3.3 MB/s with four, against 0.04–0.1 MB/s).
 */
data class DirectUpload(
    val id: String,
    /** The upload at the tus endpoint (HEAD, DELETE, download), as for any upload. */
    val location: String,
    val size: Long,
    val partSize: Long,
    val parts: Int,
    /** Part number → ETag, for the parts R2 has taken. */
    val etags: Map<Int, String> = emptyMap(),
) {
    fun start(part: Int): Long = (part - 1) * partSize
    fun length(part: Int): Long = minOf(partSize, size - start(part))
    val pending: List<Int> get() = (1..parts).filter { it !in etags }
    val sent: Long get() = etags.keys.sumOf { length(it) }
    val done: Boolean get() = etags.size == parts

    fun with(part: Int, etag: String) = copy(etags = etags + (part to etag))

    /** The body of `POST /direct/<id>/complete`: every part in order with its ETag. */
    fun completeBody(): String = JSONObject().put("parts", JSONArray().apply {
        for (n in 1..parts) put(JSONObject().put("part", n).put("etag", etags.getValue(n)))
    }).toString()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("location", location).put("size", size).put("partSize", partSize)
        .put("parts", parts).put("etags", JSONObject().apply { etags.forEach { (n, e) -> put(n.toString(), e) } })

    companion object {
        fun fromJson(o: JSONObject): DirectUpload? = runCatching {
            val e = o.optJSONObject("etags")
            DirectUpload(o.getString("id"), o.getString("location"), o.getLong("size"), o.getLong("partSize"), o.getInt("parts"),
                e?.keys()?.asSequence()?.associate { it.toInt() to e.getString(it) } ?: emptyMap())
        }.getOrNull()

        /** `<origin>/direct` + [path] for the tus [endpoint] (the same server). */
        fun url(endpoint: String, path: String = ""): String? {
            val e = Tus.endpoint(endpoint) ?: return null
            val u = runCatching { URI(e) }.getOrNull() ?: return null
            return URI(u.scheme, null, u.host, u.port, "/direct$path", null, null).toString()
        }

        /** `POST /direct`'s answer: the upload and the URLs it came with (part → URL). */
        fun parseCreate(body: String): Pair<DirectUpload, Map<Int, String>> {
            val o = JSONObject(body)
            val d = DirectUpload(o.getString("id"), o.getString("location"), o.getLong("size"), o.getLong("partSize"), o.getInt("parts"))
            require(d.partSize > 0 && d.parts == ((d.size + d.partSize - 1) / d.partSize).toInt().coerceAtLeast(1)) { "direct: parts do not add up" }
            return d to parseUrls(body)
        }

        fun parseUrls(body: String): Map<Int, String> {
            val a = JSONObject(body).optJSONArray("urls") ?: return emptyMap()
            return (0 until a.length()).associate { a.getJSONObject(it).let { u -> u.getInt("part") to u.getString("url") } }
        }

        fun createBody(size: Long, metadata: Map<String, String?>): String =
            JSONObject().put("size", size).put("metadata", JSONObject().apply { metadata.forEach { (k, v) -> if (v != null) put(k, v) } }).toString()

        fun urlsBody(parts: List<Int>): String = JSONObject().put("parts", JSONArray(parts)).toString()
    }
}

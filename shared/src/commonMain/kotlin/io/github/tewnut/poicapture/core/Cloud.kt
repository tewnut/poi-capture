package io.github.tewnut.poicapture.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The pure side of talking to the team's server (server/README.md), without java.net or org.json, so the iPhone app
 * uses the same rules as the Android app (app core Tus.kt, SetupLink.kt, Direct.kt; those stay as they are, these are
 * checked against them in app/src/test CloudParityTest): the endpoint, the headers, the setup QR / link, who a token
 * is (GET /me) and the bookkeeping of a direct upload (presigned R2 parts). The HTTP calls live in each app.
 */
object Cloud {
    const val TUS_VERSION = "1.0.0"

    /** The project's server (Cloudflare Worker + R2); the same default as the Android app. */
    const val DEFAULT_URL = "https://poi-uploader.4hum.ai/files/"

    /** The endpoint as typed, trimmed, with one trailing slash; null when it is not an http(s) URL with a host. */
    fun endpoint(typed: String): String? {
        val t = typed.trim()
        if (t.isEmpty()) return null
        val u = parseUrl(t) ?: return null
        if (u.scheme.lowercase() !in setOf("http", "https") || u.host.isEmpty()) return null
        return t.trimEnd('/') + "/"
    }

    /** "Bearer x" for a token typed with or without the word Bearer; null for none. */
    fun authHeader(token: String?): String? {
        val t = token?.trim()?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
        return if (t.isNullOrEmpty()) null else "Bearer $t"
    }

    /**
     * The `X-Client` header (server/README.md "Last seen") of the iPhone app: `POI Capture/<version> (iOS <system>;
     * Apple <model>)`, printable ASCII only (anything else becomes '?', blanks collapse), at most 120 characters.
     */
    fun clientHeader(version: String, system: String, model: String): String {
        fun clean(s: String) = s.map { if (it in ' '..'~') it else '?' }.joinToString("").replace(Regex("\\s+"), " ").trim()
        val h = "POI Capture/${clean(version)} (iOS ${clean(system)}; Apple ${clean(model)})"
        return h.take(120).trim()
    }

    /** A usable endpoint is set; the default server also needs its token (it answers 401 without one). */
    fun configured(url: String, token: String): Boolean {
        val e = endpoint(url) ?: return false
        return e != endpoint(DEFAULT_URL) || token.isNotBlank()
    }

    /** `https://h.example:8443/files/` → `https://h.example:8443/me`; null when not an http(s) URL. */
    fun meUrl(endpoint: String): String? = origin(endpoint)?.let { "$it/me" }

    /** `<origin>/direct` + [path] for the tus [endpoint] (the same server). */
    fun directUrl(endpoint: String, path: String): String? = origin(endpoint)?.let { "$it/direct$path" }

    private fun origin(endpoint: String): String? {
        val e = endpoint(endpoint) ?: return null
        val u = parseUrl(e) ?: return null
        return u.scheme + "://" + u.host + (if (u.port != null) ":${u.port}" else "")
    }

    /**
     * tus `Upload-Metadata`: `key base64(value)` pairs, comma separated (tus 1.0 creation extension); keys with blanks
     * or commas are left out (they cannot be sent).
     */
    fun tusMetadata(meta: Map<String, String>): String =
        meta.filterKeys { k -> k.isNotEmpty() && k.none { it == ' ' || it == ',' } }
            .entries.joinToString(",") { (k, v) -> "$k ${base64(v.encodeToByteArray())}" }

    fun base64(bytes: ByteArray): String {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(a[b0 shr 2])
            sb.append(a[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            sb.append(if (b1 < 0) '=' else a[((b1 and 15) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            sb.append(if (b2 < 0) '=' else a[b2 and 63])
            i += 3
        }
        return sb.toString()
    }

    // ---- setup QR / link: `<tus endpoint>#token=<secret>&name=<url-encoded token name>` (server/README.md)

    /**
     * The setup QR / link split into the server address (before `#`), the token and its name. [CloudSetup.problem]:
     * "notHttp" (no http(s) address) or "noToken" (no usable token); null when it is fine. Same rules as the
     * Android app's SetupLink.parse.
     */
    fun parseSetup(text: String?): CloudSetup {
        val t = text?.trim().orEmpty()
        val hash = t.indexOf('#')
        val base = if (hash < 0) t else t.substring(0, hash)
        val endpoint = endpoint(base) ?: return CloudSetup("", "", null, "notHttp")
        val params = if (hash < 0) emptyMap() else fragmentParams(t.substring(hash + 1))
        val token = params["token"]?.trim().orEmpty()
        if (token.isEmpty() || token.any { it.isWhitespace() || it.isISOControl() }) return CloudSetup(endpoint, "", null, "noToken")
        val name = params["name"]?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        return CloudSetup(endpoint, token, name, null)
    }

    /** `a=1&b=x%20y` → map; a key without a value or with bad %-escapes is left out; the first of a repeated key wins. */
    private fun fragmentParams(fragment: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in fragment.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val k = formDecode(pair.substring(0, i)) ?: continue
            val v = formDecode(pair.substring(i + 1)) ?: continue
            if (k !in out) out[k] = v
        }
        return out
    }

    /** As java.net.URLDecoder: '+' is a blank, %XX are UTF-8 bytes; null for a broken escape. */
    fun formDecode(s: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { out.append(' '); i++ }
                c == '%' -> {
                    val bytes = ArrayList<Byte>()
                    while (i < s.length && s[i] == '%') {
                        if (i + 2 >= s.length) return null
                        val h = hex(s[i + 1]); val l = hex(s[i + 2])
                        if (h < 0 || l < 0) return null
                        bytes.add(((h shl 4) or l).toByte())
                        i += 3
                    }
                    out.append(bytes.toByteArray().decodeToString())
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    // ---- who a token is: GET <origin>/me → {"schema": "poi-cloud-me/1", "id", "name", "scopes", "admin", "role"}

    /** null for anything but a poi-cloud-me/1 answer with a role or scopes (e.g. a plain tus server's page). */
    fun parseMe(body: String?): CloudIdentity? {
        val o = runCatching { Json.parseToJsonElement(body ?: return null).jsonObject }.getOrNull() ?: return null
        if (o.str("schema") != "poi-cloud-me/1") return null
        val name = o.str("name")?.trim().orEmpty()
        val role = o.str("role")?.trim().orEmpty()
        val arr = runCatching { o["scopes"]?.jsonArray }.getOrNull()
        if (role.isEmpty() && arr == null) return null
        val scopes = arr?.mapNotNull { e -> runCatching { e.jsonPrimitive.contentOrNull?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() } }?.distinct()
            ?: when (role) {
                "uploader" -> listOf("upload")
                "reviewer" -> listOf("upload", "read", "process")
                "admin" -> listOf("upload", "read", "process", "delete")
                else -> emptyList()
            }
        val admin = runCatching { o["admin"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: (role == "admin")
        val id = o.str("id").orEmpty()
        return CloudIdentity(id, name.ifEmpty { id }, role, scopes, admin)
    }

    // ---- a tiny URL reader: scheme, host, port (enough for the endpoint checks above)

    internal class Url(val scheme: String, val host: String, val port: Int?)

    /** null when [s] is not an absolute URL (as java.net.URI would refuse it: blanks and the like inside). */
    internal fun parseUrl(s: String): Url? {
        if (s.any { it <= ' ' || it in "\"<>\\^`{|}" || it.code == 127 }) return null
        val colon = s.indexOf(':')
        if (colon <= 0) return null
        val scheme = s.substring(0, colon)
        if (!scheme[0].isLetter() || scheme.any { !(it.isLetterOrDigit() || it in "+-.") }) return null
        val rest = s.substring(colon + 1)
        if (!rest.startsWith("//")) return Url(scheme, "", null)
        val afterSlashes = rest.substring(2)
        val end = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) afterSlashes.length else it }
        var authority = afterSlashes.substring(0, end)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        var host = authority
        var port: Int? = null
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1)
            val tail = authority.substring(close + 1)
            if (tail.startsWith(":") && tail.length > 1) port = tail.substring(1).toIntOrNull() ?: return null
        } else {
            val c = authority.lastIndexOf(':')
            if (c >= 0) {
                host = authority.substring(0, c)
                val p = authority.substring(c + 1)
                if (p.isNotEmpty()) port = p.toIntOrNull() ?: return Url(scheme, "", null)
            }
        }
        // java.net.URI gives no host for names it cannot read as a server (e.g. an underscore): treat as none
        if (!host.startsWith("[") && host.any { !(it.isLetterOrDigit() || it == '-' || it == '.') }) host = ""
        return Url(scheme, host, port)
    }

    internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}

/** The setup QR / link split up ([Cloud.parseSetup]); [problem] "notHttp" | "noToken" | null. Never prints the token. */
class CloudSetup(val endpoint: String, val token: String, val name: String?, val problem: String?) {
    val ok: Boolean get() = problem == null
    override fun toString() = "CloudSetup(endpoint=$endpoint, name=$name, problem=$problem)"
}

/** Who the server says a token belongs to and what it may do ([Cloud.parseMe]). */
class CloudIdentity(val id: String, val name: String, val role: String, val scopes: List<String>, val admin: Boolean) {
    val canUpload: Boolean get() = "upload" in scopes
}

/** One presigned part URL of a direct upload. */
class DirectUrl(val part: Int, val url: String)

/**
 * A direct upload (server/README.md, "Direct uploads"), as the Android app's DirectUpload: the server opens an R2
 * multipart upload and hands out presigned URLs, one per [partSize] part; the phone PUTs the parts straight to R2,
 * keeps each part's ETag ([etags]), and asks the server to complete. Kept as JSON ([toJson]) so an upload resumes after
 * the app restarts.
 */
class DirectState(val id: String, val location: String, val size: Long, val partSize: Long, val parts: Int,
                  val etags: Map<Int, String> = emptyMap()) {
    fun start(part: Int): Long = (part - 1) * partSize
    fun length(part: Int): Long = minOf(partSize, size - start(part))
    val pending: List<Int> get() = (1..parts).filter { it !in etags }
    val sent: Long get() = etags.keys.sumOf { length(it) }
    val done: Boolean get() = etags.size == parts

    fun withEtag(part: Int, etag: String) = DirectState(id, location, size, partSize, parts, etags + (part to etag))

    /** The body of `POST /direct/<id>/complete`: every part in order with its ETag. */
    fun completeBody(): String = buildJsonObject {
        putJsonArray("parts") { for (n in 1..parts) add(buildJsonObject { put("part", n); put("etag", etags[n] ?: "") }) }
    }.toString()

    fun toJson(): String = buildJsonObject {
        put("id", id); put("location", location); put("size", size); put("partSize", partSize); put("parts", parts)
        putJsonObject("etags") { etags.forEach { (n, e) -> put(n.toString(), e) } }
    }.toString()
}

object Direct {
    /** `POST /direct` body: the zip's size and its metadata (null values left out by the caller). */
    fun createBody(size: Long, metadata: Map<String, String>): String = buildJsonObject {
        put("size", size)
        putJsonObject("metadata") { metadata.forEach { (k, v) -> put(k, v) } }
    }.toString()

    /** `POST /direct`'s answer; null when it is not one or its parts do not add up. */
    fun parseCreate(body: String): DirectState? = runCatching {
        val o = Json.parseToJsonElement(body).jsonObject
        val d = DirectState(o["id"]!!.jsonPrimitive.content, o["location"]!!.jsonPrimitive.content,
            o["size"]!!.jsonPrimitive.longOrNull!!, o["partSize"]!!.jsonPrimitive.longOrNull!!, o["parts"]!!.jsonPrimitive.intOrNull!!)
        val expected = ((d.size + d.partSize - 1) / d.partSize).toInt().coerceAtLeast(1)
        if (d.partSize <= 0 || d.parts != expected) null else d
    }.getOrNull()

    /** The `urls` of a `POST /direct` or `/direct/<id>/urls` answer. */
    fun parseUrls(body: String): List<DirectUrl> = runCatching {
        val a = Json.parseToJsonElement(body).jsonObject["urls"]?.jsonArray ?: JsonArray(emptyList())
        a.mapNotNull { e ->
            val u = e.jsonObject
            val n = u["part"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val url = u["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            DirectUrl(n, url)
        }
    }.getOrDefault(emptyList())

    fun urlsBody(parts: List<Int>): String = buildJsonObject { put("parts", buildJsonArray { parts.forEach { add(JsonPrimitive(it)) } }) }.toString()

    /** A saved [DirectState.toJson]; null when unreadable. */
    fun state(json: String): DirectState? = runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        val e = o["etags"]?.jsonObject
        DirectState(o["id"]!!.jsonPrimitive.content, o["location"]!!.jsonPrimitive.content, o["size"]!!.jsonPrimitive.longOrNull!!,
            o["partSize"]!!.jsonPrimitive.longOrNull!!, o["parts"]!!.jsonPrimitive.intOrNull!!,
            e?.entries?.associate { (k, v) -> k.toInt() to v.jsonPrimitive.content } ?: emptyMap())
    }.getOrNull()
}

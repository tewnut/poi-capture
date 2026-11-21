package io.github.tewnut.poicapture.core

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * The setup QR / link an admin hands to a phone (server/README.md, "Tokens and roles"):
 * `<tus endpoint>#token=<secret>&name=<url-encoded token name>`. The part before `#` is the server address, `token`
 * the access token, `name` the token's label. Pure, so it is tested on the JVM; the token is never put in a message.
 */
data class SetupLink(val endpoint: String, val token: String, val name: String?) {
    /** Never prints the token (data class toString would). */
    override fun toString() = "SetupLink(endpoint=$endpoint, name=$name)"

    enum class Problem { NOT_HTTP, NO_TOKEN }

    sealed class Parsed {
        data class Ok(val link: SetupLink) : Parsed()
        data class Bad(val problem: Problem) : Parsed()
    }

    companion object {
        fun parse(text: String?): Parsed {
            val t = text?.trim().orEmpty()
            val hash = t.indexOf('#')
            val base = if (hash < 0) t else t.substring(0, hash)
            val endpoint = Tus.endpoint(base) ?: return Parsed.Bad(Problem.NOT_HTTP)
            val params = if (hash < 0) emptyMap() else fragmentParams(t.substring(hash + 1))
            val token = params["token"]?.trim().orEmpty()
            if (token.isEmpty() || token.any { it.isWhitespace() || it.isISOControl() }) return Parsed.Bad(Problem.NO_TOKEN)
            val name = params["name"]?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
            return Parsed.Ok(SetupLink(endpoint, token, name))
        }

        /** `a=1&b=x%20y` → map; a key without a value or with bad %-escapes is left out; the first of a repeated key wins. */
        private fun fragmentParams(fragment: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (pair in fragment.split('&')) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                val k = decode(pair.substring(0, i)) ?: continue
                val v = decode(pair.substring(i + 1)) ?: continue
                out.putIfAbsent(k, v)
            }
            return out
        }

        private fun decode(s: String): String? = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()
    }
}

/** Who the server says a token belongs to: `GET <origin>/me` → `{"schema": "poi-cloud-me/1", "id", "name", "role"}`. */
data class CloudMe(val id: String, val name: String, val role: String) {
    companion object {
        const val SCHEMA = "poi-cloud-me/1"

        /** `https://h.example:8443/files/` → `https://h.example:8443/me`; null when not an http(s) URL. */
        fun url(endpoint: String): String? {
            val e = Tus.endpoint(endpoint) ?: return null
            val u = runCatching { URI(e) }.getOrNull() ?: return null
            return URI(u.scheme, null, u.host, u.port, "/me", null, null).toString()
        }

        /** null for anything but a poi-cloud-me/1 answer with a name and a role (e.g. a plain tus server's page). */
        fun parse(body: String?): CloudMe? {
            val o = runCatching { JSONObject(body ?: return null) }.getOrNull() ?: return null
            if (o.optString("schema") != SCHEMA) return null
            val name = o.optString("name").trim()
            val role = o.optString("role").trim()
            if (role.isEmpty()) return null
            return CloudMe(o.optString("id"), name.ifEmpty { o.optString("id") }, role)
        }
    }
}

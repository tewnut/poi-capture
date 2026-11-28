package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Camera 360 ride (docs/camera360.md §2): the phone records the track, an external 360° camera films. The phone shows a
 * sync QR code to the camera; the processing finds it in the camera's frames and gets the camera's clock offset.
 *
 * `session.camera360` = `{"syncs": [{"fromMs", "toMs", "when"}, …]}`: when the QR screen was shown (epoch ms, the
 * phone's wall clock), so the processing knows where to look for the code.
 */
object Camera360 {
    const val SYNC_PREFIX = "QPSYNC1"
    /** The QR code is drawn again this often, each time with the current time. */
    const val REDRAW_MS = 100L

    /** The text of the sync QR code: `QPSYNC1 <sessionId> <epochMs>`. */
    fun syncText(sessionId: String, epochMs: Long): String = "$SYNC_PREFIX $sessionId $epochMs"

    /** (sessionId, epochMs) of a sync text, null when it is not one. */
    fun parseSyncText(text: String): Pair<String, Long>? {
        val p = text.trim().split(' ')
        if (p.size != 3 || p[0] != SYNC_PREFIX || p[1].isEmpty()) return null
        return p[2].toLongOrNull()?.let { p[1] to it }
    }

    /** Why the QR screen was shown. */
    enum class When(val json: String) {
        START("start"), END("end"), MANUAL("manual");

        companion object {
            fun of(s: String?) = entries.firstOrNull { it.json == s } ?: MANUAL
        }
    }

    /**
     * One showing of the QR screen. [gpsMinusPhoneMs] (optional, app notes in docs/camera360.md): the last GPS fix's
     * time (track `t`) minus the phone's wall clock at that fix, null without a fresh fix.
     */
    data class Sync(val fromMs: Long, val toMs: Long, val `when`: When, val gpsMinusPhoneMs: Long? = null) {
        fun toJson(): JSONObject = JSONObject().put("fromMs", fromMs).put("toMs", toMs).put("when", `when`.json)
            .also { if (gpsMinusPhoneMs != null) it.put("gpsMinusPhoneMs", gpsMinusPhoneMs) }

        companion object {
            fun fromJson(o: JSONObject) = Sync(o.optLong("fromMs"), o.optLong("toMs"), When.of(o.optStr("when")),
                if (o.has("gpsMinusPhoneMs") && !o.isNull("gpsMinusPhoneMs")) o.optLong("gpsMinusPhoneMs") else null)
        }
    }

    /** A fresh `session.camera360` (no sync yet). */
    fun empty(): JSONObject = JSONObject().put("syncs", JSONArray())

    /** [camera360] (null = none yet) with [sync] appended to its `syncs`; a window that ends before it starts is closed at its start. */
    fun withSync(camera360: JSONObject?, sync: Sync): JSONObject {
        val o = camera360 ?: empty()
        val a = o.optJSONArray("syncs") ?: JSONArray().also { o.put("syncs", it) }
        a.put(sync.copy(toMs = maxOf(sync.fromMs, sync.toMs)).toJson())
        return o
    }

    fun syncs(camera360: JSONObject?): List<Sync> {
        val a = camera360?.optJSONArray("syncs") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Sync::fromJson) }
    }

    /**
     * A Camera 360 ride is kept (and uploaded) when it has a track: it never has photos. Other kinds need a photo or a
     * video, as before.
     */
    fun worthKeeping(kind: SessionKind, mediaCount: Int, trackPoints: Int): Boolean =
        if (kind == SessionKind.CAMERA360) trackPoints > 0 else mediaCount > 0
}

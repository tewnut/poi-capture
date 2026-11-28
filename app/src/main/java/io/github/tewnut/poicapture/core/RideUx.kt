package io.github.tewnut.poicapture.core

import org.json.JSONObject

/** Onboarding "Dữ liệu đường": go on once a pack is on the phone or one is downloading (it finishes in the background). */
object RoadsGate {
    fun canContinue(installed: Boolean, downloading: Boolean): Boolean = installed || downloading
}

/** No usable GPS fix for a while: say where to stand ("Ra chỗ thoáng…"). */
object GpsWait {
    const val HINT_AFTER_MS = 20_000L

    /** [waitingSinceMs]: when the wait for a fix began, null = a fix is there. */
    fun showHint(waitingSinceMs: Long?, nowMs: Long): Boolean =
        waitingSinceMs != null && nowMs - waitingSinceMs >= HINT_AFTER_MS
}

/** New ride, choices folded into one line: "Quét đường · Biển báo · Kẹp cố định". */
object SetupSummary {
    enum class Part { SWEEP, PLACE, INDOOR, SHOPS, ADDRESSES, SIGNS, MOUNT, HAND, CAM360, THETA }

    fun parts(kind: SessionKind, target: SweepTarget, use360: Boolean, thetaOn: Boolean, holding: Holding): List<Part> = when (kind) {
        SessionKind.POI -> listOf(Part.PLACE)
        SessionKind.INDOOR -> listOf(Part.INDOOR)
        SessionKind.SWEEP, SessionKind.CAMERA360 -> listOf(
            Part.SWEEP,
            when (target) { SweepTarget.SHOPS -> Part.SHOPS; SweepTarget.ADDRESSES -> Part.ADDRESSES; SweepTarget.SIGNS -> Part.SIGNS },
            when {
                use360 || kind == SessionKind.CAMERA360 -> if (thetaOn) Part.THETA else Part.CAM360
                holding == Holding.HAND -> Part.HAND
                else -> Part.MOUNT
            })
    }
}

/**
 * What a ride ended with Save leaves for the summary screen: counts read off the live ride before it closed
 * (signs and Theta shots are not kept anywhere else). [signs] only on a signs ride, [plates] (photos with a house
 * number) only on a house-number ride, [thetaShots] / [thetaSkipped] / [thetaFailed] only on a Theta ride.
 */
data class RideEnd(val sessionId: String, val cam360: Boolean, val photos: Int, val videos: Int,
                   val signs: Int? = null, val thetaShots: Int? = null, val thetaSkipped: Int? = null,
                   val plates: Int? = null, val thetaFailed: Int? = null) {
    /** One line for rememberSaveable (the summary survives a rotation). */
    fun encode(): String = listOf(sessionId, if (cam360) "1" else "0", photos, videos, signs ?: -1, thetaShots ?: -1, thetaSkipped ?: -1,
        plates ?: -1, thetaFailed ?: -1).joinToString("|")

    companion object {
        fun decode(s: String?): RideEnd? {
            val p = s?.split('|') ?: return null
            // 7 fields: saved before plates / thetaFailed were kept
            if ((p.size != 7 && p.size != 9) || p[0].isEmpty()) return null
            val n = p.drop(2).map { it.toIntOrNull() ?: return null }
            fun opt(v: Int) = v.takeIf { it >= 0 }
            return RideEnd(p[0], p[1] == "1", n[0], n[1], opt(n[2]), opt(n[3]), opt(n[4]),
                n.getOrNull(5)?.let { opt(it) }, n.getOrNull(6)?.let { opt(it) })
        }
    }
}

/**
 * A ride reopened after Android killed the app: the service starts again on a session that already started, with
 * fresh counters. The counts go on from what is on disk (items, track, the counts saved last), so the end dialog, the
 * summary and `session.counts` are the whole ride's, and the "nothing recorded" shortcut never deletes it.
 */
object RideRestore {
    data class Counts(val photos: Int = 0, val videos: Int = 0, val trackPoints: Int = 0, val plates: Int = 0, val signs: Int = 0)

    /** From the session's [items] (patches applied), its track's line count and `session.counts` ([saved]). */
    fun counts(items: List<JSONObject>, trackLines: Int, saved: JSONObject?): Counts {
        val videos = items.count { it.optString("kind") == "video" }
        val photos = items.size - videos
        // as counted live: a photo the phone read a house number on, a photo with a sign seen
        val plates = items.count { i -> i.optJSONArray("plates")?.let { a ->
            (0 until a.length()).any { a.optJSONObject(it)?.optString("kind") == HouseNumbers.Kind.NUMBER.json } } == true }
        val signs = items.count { i -> i.optJSONArray("signs")?.let { a ->
            (0 until a.length()).any { (a.optJSONObject(it)?.optDouble("score") ?: 0.0) >= SignBoxes.SEEN } } == true }
        fun savedOf(k: String) = saved?.optInt(k, 0) ?: 0
        return Counts(maxOf(photos, savedOf("photos")), maxOf(videos, savedOf("videos")), maxOf(trackLines, savedOf("trackPoints")),
            plates, signs)
    }

    /**
     * Leaving with "nothing recorded" may drop the session without asking only when it never started and holds no
     * photo or video ([mediaFiles]: items plus files on disk). The track alone of a ride that never started is only
     * the wait for GPS / light.
     */
    fun deletable(startedAt: String?, mediaFiles: Int): Boolean = startedAt == null && mediaFiles == 0
}

/**
 * Camera 360 sync QR (docs/camera360.md §2): a showing counts only after [MIN_MS] on the screen, long enough for the
 * camera to film it; one tapped away at once aligns nothing.
 */
object SyncGate {
    const val MIN_MS = 3_000L

    fun counts(fromMs: Long, toMs: Long): Boolean = toMs - fromMs >= MIN_MS

    /** Whole seconds still to wait (shown on Done), 0 = it counts. */
    fun secondsLeft(fromMs: Long, nowMs: Long): Int = ((MIN_MS - (nowMs - fromMs)).coerceAtLeast(0) + 999).toInt() / 1000

    /** The video can be put on the track: at least one showing long enough. */
    fun aligned(syncs: List<Camera360.Sync>): Boolean = syncs.any { counts(it.fromMs, it.toMs) }
}

/**
 * The upload of a ride just ended, as the summary shows it. A sweep may be cut into parts "<id>-k" by its upload
 * ([SessionSplit]); a session sent in full may be removed from the phone ([io.github.tewnut.poicapture.capture.UploadServer.removeAfter]):
 * a ride or part that was there and is gone counts as sent.
 */
object RideUpload {
    enum class Kind { NO_TOKEN, NOT_SENT, WAITING, WIFI, SENDING, SENT, FAILED }

    /** One session's upload: [exists] in the store, its record, and its background work if any. */
    data class Part(val exists: Boolean, val status: UploadStatus = UploadStatus.NONE, val queued: Boolean = false,
                    val running: Boolean = false, val wifiWait: Boolean = false, val error: String? = null,
                    val done: Long = 0, val total: Long = 0, val problem: String? = null)

    /** [problem]: [UploadRecord.problem] of a part not sent yet, when the contributor has to act (access key, space). */
    data class State(val kind: Kind, val pct: Int = 0, val error: String? = null, val problem: String? = null)

    /** The sessions to follow: the ride itself until its parts appear, then every part ever seen. */
    fun ids(sessionId: String, partsNow: Collection<String>, seenBefore: Collection<String>): List<String> {
        val parts = (seenBefore.toSet() + partsNow).filter { it != sessionId }
        return if (parts.isEmpty()) listOf(sessionId) else parts.sorted()
    }

    fun kindOf(p: Part): Kind = when {
        !p.exists || p.status == UploadStatus.DONE -> Kind.SENT
        p.running -> Kind.SENDING
        p.queued && p.error != null -> Kind.FAILED   // it will try again by itself; Thử lại sends now
        p.wifiWait -> Kind.WIFI
        p.queued || p.status == UploadStatus.QUEUED || p.status == UploadStatus.UPLOADING -> Kind.WAITING
        p.status == UploadStatus.FAILED -> Kind.FAILED
        else -> Kind.NOT_SENT
    }

    /** Worst first: a failure, then sending, waiting for Wi-Fi, waiting, not sent; sent only when every part is. */
    fun of(configured: Boolean, parts: List<Part>): State {
        if (!configured) return State(Kind.NO_TOKEN)
        if (parts.isEmpty()) return State(Kind.NOT_SENT)
        val kinds = parts.map { kindOf(it) }
        val order = listOf(Kind.FAILED, Kind.SENDING, Kind.WIFI, Kind.WAITING, Kind.NOT_SENT, Kind.SENT)
        val kind = order.first { it in kinds }
        val error = if (kind == Kind.FAILED) parts.firstOrNull { kindOf(it) == Kind.FAILED }?.error else null
        // bytes over the parts that know their size; a part sent counts in full
        val sized = parts.filter { it.exists && it.total > 0 }
        val total = sized.sumOf { it.total }
        val done = sized.sumOf { if (it.status == UploadStatus.DONE) it.total else it.done.coerceIn(0, it.total) }
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val problem = parts.firstOrNull { kindOf(it) != Kind.SENT && it.problem != null }?.problem
        return State(kind, pct, error, problem)
    }
}

package io.github.tewnut.poicapture.core

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
 * (signs and Theta shots are not kept anywhere else). [signs] only on a signs ride, [thetaShots] / [thetaSkipped]
 * only on a Theta ride.
 */
data class RideEnd(val sessionId: String, val cam360: Boolean, val photos: Int, val videos: Int,
                   val signs: Int? = null, val thetaShots: Int? = null, val thetaSkipped: Int? = null) {
    /** One line for rememberSaveable (the summary survives a rotation). */
    fun encode(): String = listOf(sessionId, if (cam360) "1" else "0", photos, videos, signs ?: -1, thetaShots ?: -1, thetaSkipped ?: -1)
        .joinToString("|")

    companion object {
        fun decode(s: String?): RideEnd? {
            val p = s?.split('|') ?: return null
            if (p.size != 7 || p[0].isEmpty()) return null
            val n = p.drop(2).map { it.toIntOrNull() ?: return null }
            fun opt(v: Int) = v.takeIf { it >= 0 }
            return RideEnd(p[0], p[1] == "1", n[0], n[1], opt(n[2]), opt(n[3]), opt(n[4]))
        }
    }
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
                    val done: Long = 0, val total: Long = 0)

    data class State(val kind: Kind, val pct: Int = 0, val error: String? = null)

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
        return State(kind, pct, error)
    }
}

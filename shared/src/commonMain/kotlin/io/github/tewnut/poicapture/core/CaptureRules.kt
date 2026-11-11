package io.github.tewnut.poicapture.core

/** Format of the exported session (manifest.json `schema`). "road-capture/N" before app 0.8, same format. */
const val SCHEMA = "poi-capture/3"

/** Rules both apps follow, so a session means the same whichever phone recorded it. */
object CaptureRules {
    /** A photo needs a GPS fix at most this old (else the capture holds and says "GPS lost"). */
    const val GPS_MAX_AGE_MS = 5_000L
    /** Place capture on foot: the place is about this far in front of the camera when nothing measured it. */
    const val ON_FOOT_FACADE_M = 5.0
    /** A photo whose GPS accuracy is worse than this is flagged weak. */
    const val MAX_ACCURACY_M = 30.0

    fun gpsFresh(fixAgeMs: Long): Boolean = fixAgeMs in 0..GPS_MAX_AGE_MS
}

/** ISO-8601 UTC time of epoch milliseconds, as java.time.Instant prints it ("…T17:30:00Z", "…T17:30:00.250Z"). */
object IsoTime {
    fun format(ms: Long): String {
        val days = floorDiv(ms, 86_400_000L)
        val msOfDay = ms - days * 86_400_000L
        // civil date from days since 1970-01-01 (Howard Hinnant's algorithm)
        val z = days + 719_468
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        val h = msOfDay / 3_600_000; val mi = msOfDay / 60_000 % 60; val s = msOfDay / 1000 % 60; val milli = msOfDay % 1000
        fun p(v: Long, n: Int) = v.toString().padStart(n, '0')
        val frac = if (milli == 0L) "" else "." + p(milli, 3)
        return "${p(y, 4)}-${p(m, 2)}-${p(d, 2)}T${p(h, 2)}:${p(mi, 2)}:${p(s, 2)}${frac}Z"
    }

    private fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q }
}

/** A session's name: "{street} [yymmdd]", the street most of its photos were taken on, else [fallback]. */
object SessionNaming {
    /** How photos on a way without a name or ref are labelled (Road.kt `roadLabel`). */
    const val UNNAMED_PREFIX = "Unnamed road"

    fun isNamed(street: String): Boolean = street.isNotBlank() && street != "null" && !street.startsWith(UNNAMED_PREFIX)

    /**
     * "{street} [yymmdd]": the named street most photos were taken on. Unnamed lanes never name a session (first
     * Place capture: 3 photos in an unnamed lane, 2 on Mai Chí Thọ → "Unnamed road (residential)"); without any named
     * street, [nearby] (the nearest big named road), then [fallback].
     */
    fun name(streets: List<String>, yymmdd: String?, fallback: String, nearby: String? = null): String {
        val street = streets.map { it.trim() }.filter { isNamed(it) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: nearby?.takeIf { isNamed(it) } ?: fallback
        return if (yymmdd.isNullOrBlank()) street else "$street [$yymmdd]"
    }
}

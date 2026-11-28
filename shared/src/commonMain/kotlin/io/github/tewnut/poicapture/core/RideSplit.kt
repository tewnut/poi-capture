package io.github.tewnut.poicapture.core

/**
 * Where a street sweep is cut into parts before it is uploaded: one part per street, and a new part after a long stop.
 * Each part becomes a session of its own on the server, so one reviewer can take one street.
 *
 * A part starts where the ride turns onto another street that holds for at least [MIN_PHOTOS] photos (crossing an
 * intersection, or a photo or two matched to a side street, does not count), or after more than [GAP_MS] between two
 * photos. Photos on an unnamed road belong to the street around them. A part shorter than [MIN_PHOTOS] joins its
 * neighbour, so a ride is never cut into scraps.
 */
object RideSplit {
    const val GAP_MS = 3 * 60_000L
    const val MIN_PHOTOS = 12

    /** One photo, in riding order: the street it was taken on ("null", blank or unnamed = none) and its time. */
    data class Photo(val street: String?, val tMs: Long?)

    /** Index ranges into [photos], one per part; a single range = no split. */
    fun parts(photos: List<Photo>, gapMs: Long = GAP_MS, minPhotos: Int = MIN_PHOTOS): List<IntRange> {
        if (photos.isEmpty()) return emptyList()
        val road = streets(photos.map { it.street }, minPhotos)
        val cuts = mutableListOf(0)
        for (i in 1 until photos.size) {
            val a = photos[i - 1].tMs
            val b = photos[i].tMs
            val gap = a != null && b != null && b - a > gapMs
            if (gap || road[i] != road[i - 1]) cuts += i
        }
        val raw = cuts.mapIndexed { k, c -> c..(if (k + 1 < cuts.size) cuts[k + 1] - 1 else photos.size - 1) }
        val merged = mutableListOf<IntRange>()
        for (p in raw) {   // a part too short (a stop cut it, or it is the last one) joins the one before
            val last = merged.lastOrNull()
            if (last != null && (p.count() < minPhotos || last.count() < minPhotos)) merged[merged.size - 1] = last.first..p.last
            else merged += p
        }
        return merged
    }

    /** The part's street: the one most of its photos were taken on, after smoothing (null: none named). */
    fun street(photos: List<Photo>, range: IntRange, minPhotos: Int = MIN_PHOTOS): String? =
        streets(photos.map { it.street }, minPhotos).slice(range).filterNotNull()
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * Each photo's street, smoothed: an unnamed photo takes the street before it (after it, at the start), and a street
     * held for fewer than [minPhotos] photos in a row takes the one before it (after it, at the start).
     */
    fun streets(raw: List<String?>, minPhotos: Int = MIN_PHOTOS): List<String?> {
        val road = raw.map { s -> s?.trim()?.takeIf { SessionNaming.isNamed(it) } }.toMutableList()
        var last: String? = null
        for (i in road.indices) { road[i] = road[i] ?: last; last = road[i] }
        var next: String? = null
        for (i in road.indices.reversed()) { road[i] = road[i] ?: next; next = road[i] }
        while (true) {
            val runs = mutableListOf<Triple<String?, Int, Int>>()
            for ((i, r) in road.withIndex()) {
                val l = runs.lastOrNull()
                if (l != null && l.first == r) runs[runs.size - 1] = l.copy(third = i) else runs += Triple(r, i, i)
            }
            if (runs.size < 2) break
            val k = runs.indexOfFirst { it.third - it.second + 1 < minPhotos }
            if (k < 0) break
            val fill = if (k > 0) runs[k - 1].first else runs[k + 1].first
            for (i in runs[k].second..runs[k].third) road[i] = fill
        }
        return road
    }
}

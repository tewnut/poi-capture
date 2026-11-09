package io.github.tewnut.poicapture.core

/** Pure helpers of the first-run flow (email → permissions → road data → ready). */
object Onboarding {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s.]{2,}$")

    /** Format check only: the email is not verified, it groups and credits a contributor's sessions. */
    fun isEmail(s: String): Boolean = EMAIL.matches(s.trim())

    /** What goes in `session.contributor`: the name typed, else the part of the email before `@`. */
    fun displayName(name: String, email: String): String = name.trim().ifBlank { email.trim().substringBefore('@') }

    /** A region box of the pack catalogue: south, west, north, east. */
    data class Box(val south: Double, val west: Double, val north: Double, val east: Double) {
        fun covers(lat: Double, lon: Double) = lat in south..north && lon in west..east
        val area get() = (north - south) * (east - west)
    }

    /**
     * The pack to suggest at a position: the smallest region covering it (a city centre before the whole province:
     * quicker to download, and enough for a first ride). Null when no pack covers it.
     */
    fun <T> suggest(items: List<T>, lat: Double, lon: Double, box: (T) -> Box): T? =
        items.filter { box(it).covers(lat, lon) }.minByOrNull { box(it).area }

    /**
     * The province pack at a position: the one whose outline contains it (outlines of neighbouring provinces do not
     * overlap; their boxes do), else, near a border the simplified outline misses or for packs without an outline,
     * the smallest box covering it.
     */
    fun <T> locate(items: List<T>, lat: Double, lon: Double, outline: (T) -> List<List<LatLon>>, box: (T) -> Box): T? =
        items.filter { outline(it).isNotEmpty() && inRings(lat, lon, outline(it)) }.minByOrNull { box(it).area }
            ?: suggest(items, lat, lon, box)

    /** Point in a set of rings (even-odd over all of them: islands are rings of their own). */
    fun inRings(lat: Double, lon: Double, rings: List<List<LatLon>>): Boolean {
        var inside = false
        for (ring in rings) {
            var j = ring.size - 1
            for (i in ring.indices) {
                val a = ring[i]; val b = ring[j]
                if ((a.lat > lat) != (b.lat > lat) && lon < (b.lon - a.lon) * (lat - a.lat) / (b.lat - a.lat) + a.lon) inside = !inside
                j = i
            }
        }
        return inside
    }
}

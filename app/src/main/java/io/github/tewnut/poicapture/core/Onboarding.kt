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
}

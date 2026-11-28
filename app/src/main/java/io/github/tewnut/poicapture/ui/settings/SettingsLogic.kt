package io.github.tewnut.poicapture.ui.settings

import io.github.tewnut.poicapture.net.RoadSource
import java.text.Normalizer
import java.util.Locale

/** Pure helpers of the Settings pages (search, grouping, summaries): no Android, unit-tested. */
object SettingsLogic {

    /** Lower case, no accents, đ → d, single spaces: "Thủ  Đức" → "thu duc". */
    fun fold(s: String): String {
        val noMarks = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noMarks.replace('đ', 'd').replace('Đ', 'D').lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    /**
     * Every word of [query] is found in the region's name or id, accents ignored ("thu duc" finds "Thủ Đức",
     * "hcm" finds the id "tp-hcm"). A blank query matches everything.
     */
    fun matches(name: String, id: String, query: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = fold(name) + " " + fold(id)
        return words.all { hay.contains(it) }
    }

    /** The three parts of Viet Nam, north to south. */
    enum class Zone { NORTH, CENTRAL, SOUTH }

    /**
     * Provinces after the 2025 merge (tools/roadpack/regions.json ids). The catalogue has no grouping of its own, so the
     * usual Bắc / Trung / Nam (Bắc Trung Bộ and Tây Nguyên count as Trung) is kept here, latitude for an unknown id.
     */
    private val NORTH = setOf("ha-noi", "lai-chau", "dien-bien", "son-la", "lang-son", "quang-ninh", "cao-bang", "tuyen-quang",
        "lao-cai", "thai-nguyen", "phu-tho", "bac-ninh", "hung-yen", "hai-phong", "ninh-binh")
    private val CENTRAL = setOf("thanh-hoa", "nghe-an", "ha-tinh", "quang-tri", "hue", "da-nang", "quang-ngai", "gia-lai",
        "khanh-hoa", "lam-dong", "dak-lak")
    private val SOUTH = setOf("tp-hcm", "dong-nai", "tay-ninh", "can-tho", "vinh-long", "dong-thap", "ca-mau", "an-giang")

    fun zoneOf(id: String, centreLat: Double): Zone = when (id) {
        in NORTH -> Zone.NORTH
        in CENTRAL -> Zone.CENTRAL
        in SOUTH -> Zone.SOUTH
        else -> when {
            centreLat >= 20.0 -> Zone.NORTH
            centreLat >= 11.3 -> Zone.CENTRAL
            else -> Zone.SOUTH
        }
    }

    fun zoneOf(e: RoadSource.Entry): Zone = zoneOf(e.id, (e.south + e.north) / 2)

    /** Regions matching [query], by zone (north first; empty zones left out), by name inside a zone. */
    fun group(entries: List<RoadSource.Entry>, query: String = ""): List<Pair<Zone, List<RoadSource.Entry>>> =
        entries.filter { matches(it.name, it.id, query) }
            .groupBy { zoneOf(it) }
            .toSortedMap()
            .map { (z, list) -> z to list.sortedBy { fold(it.name.ifBlank { it.id }) } }

    /** What is on the phone: how many regions, how many bytes. */
    data class Storage(val count: Int, val bytes: Long)

    fun storage(sizes: List<Long>): Storage = Storage(sizes.size, sizes.sum())

    /** "850 KB", "12.3 MB", "120 MB", "1.4 GB". */
    fun formatBytes(b: Long): String {
        val mb = b / 1048576.0
        return when {
            b < 1048576 -> String.format(Locale.ROOT, "%d KB", (b + 1023) / 1024)
            mb < 100 -> String.format(Locale.ROOT, "%.1f MB", mb)
            mb < 1024 -> String.format(Locale.ROOT, "%.0f MB", mb)
            else -> String.format(Locale.ROOT, "%.1f GB", mb / 1024)
        }
    }

    /** Catalogue entries newer than (different from) the version installed for the same id. */
    fun updates(installed: Map<String, String>, catalogue: List<RoadSource.Entry>): List<RoadSource.Entry> =
        catalogue.filter { e -> installed[e.id]?.let { it != e.version && e.version.isNotBlank() } == true }

    /** An email shown as "n•••@gmail.com"; any other contributor ID as typed. */
    fun maskContributor(id: String): String {
        val at = id.indexOf('@')
        if (at <= 0) return id
        return id.first() + "•••" + id.substring(at)
    }
}

package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Something already mapped inside a building (Simple Indoor Tagging): a room / area / corridor outline or a shop
 * (node or area) with its `level`, drawn on the capture map for the walker's current level.
 */
data class IndoorFeature(
    val osm: String,                 // "node/1", "way/2"
    val kind: String,                // "shop" | "room" | "area" | "corridor"
    val name: String?,
    val level: String?,              // the OSM value as mapped ("0", "-1", "0;1")
    val unit: String?,               // addr:unit / ref
    val tag: String?,                // "shop=clothes", "amenity=cafe"
    val geometry: List<LatLon>,      // one point for a node
) {
    val levels: List<Int> get() = Levels.osmLevels(level)
    fun onLevel(l: Int) = levels.contains(l)
    val center: LatLon get() = LatLon(geometry.map { it.lat }.average(), geometry.map { it.lon }.average())

    fun toJson(): JSONObject = JSONObject().put("osm", osm).put("kind", kind).put("name", name.orNull())
        .put("level", level.orNull()).put("unit", unit.orNull()).put("tag", tag.orNull())
        .put("geometry", Building.ringJson(geometry))

    companion object {
        fun fromJson(o: JSONObject) = IndoorFeature(o.optString("osm"), o.optString("kind"), o.optStr("name"),
            o.optStr("level"), o.optStr("unit"), o.optStr("tag"), Building.ring(o.optJSONArray("geometry")))
    }
}

/**
 * The building of an indoor walk (docs/indoor.md §4-5), loaded from OpenStreetMap at setup and kept in
 * `session.building`: footprint (the largest outer ring), all outer rings and holes, name, levels, address, and what
 * is mapped inside it.
 */
data class Building(
    val osm: String,                         // "way/123456" | "relation/42"
    val name: String?,
    val levels: Int?,                        // building:levels (above ground, ground included)
    val underground: Int?,                   // building:levels:underground
    val outer: List<List<LatLon>>,
    val holes: List<List<LatLon>> = emptyList(),
    val addr: Map<String, String> = emptyMap(),
    val minLevel: String? = null,
    val maxLevel: String? = null,
    val features: List<IndoorFeature> = emptyList(),
    val levelsSeen: List<String> = emptyList(),
    /** floor plans photographed on the walk (docs/indoor.md §7) */
    val plans: List<FloorPlanEntry> = emptyList(),
) {
    val footprint: List<LatLon> get() = outer.maxByOrNull { ringArea(it) } ?: emptyList()
    val indoorMapped: Boolean get() = features.any { it.level != null }
    val center: LatLon get() = footprint.let { r -> LatLon(r.map { it.lat }.average(), r.map { it.lon }.average()) }

    fun contains(p: LatLon): Boolean = Onboarding.inRings(p.lat, p.lon, outer) && !Onboarding.inRings(p.lat, p.lon, holes)

    /** Distance (m) from p to the outline; 0 inside. */
    fun distanceM(p: LatLon): Double = if (contains(p)) 0.0
        else outer.mapNotNull { r -> Geo.nearestOnLine(r + r.take(1), p)?.distanceM }.minOrNull() ?: Double.MAX_VALUE

    fun toJson(): JSONObject = JSONObject()
        .put("osm", osm).put("name", name.orNull()).put("levels", levels.orNull()).put("underground", underground.orNull())
        .put("footprint", ringJson(footprint))
        .put("outer", JSONArray().apply { outer.forEach { put(ringJson(it)) } })
        .put("holes", JSONArray().apply { holes.forEach { put(ringJson(it)) } })
        .put("addr", JSONObject(addr as Map<*, *>))
        .put("minLevel", minLevel.orNull()).put("maxLevel", maxLevel.orNull())
        .put("levels_seen", JSONArray(levelsSeen))
        .put("indoorMapped", indoorMapped)
        .put("features", JSONArray().apply { features.forEach { put(it.toJson()) } })
        .put("plans", FloorPlanEntry.listJson(plans))

    companion object {
        fun fromJson(o: JSONObject?): Building? {
            if (o == null) return null
            val outer = rings(o.optJSONArray("outer")).ifEmpty { listOf(ring(o.optJSONArray("footprint"))).filter { it.size >= 3 } }
            if (outer.isEmpty()) return null
            val addr = o.optJSONObject("addr")?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } } ?: emptyMap()
            val f = o.optJSONArray("features")
            val seen = o.optJSONArray("levels_seen")
            return Building(o.optString("osm"), o.optStr("name"),
                o.optInt("levels", 0).takeIf { !o.isNull("levels") && it > 0 },
                o.optInt("underground", 0).takeIf { !o.isNull("underground") && it > 0 },
                outer, rings(o.optJSONArray("holes")), addr, o.optStr("minLevel"), o.optStr("maxLevel"),
                (0 until (f?.length() ?: 0)).map { IndoorFeature.fromJson(f!!.getJSONObject(it)) },
                (0 until (seen?.length() ?: 0)).map { seen!!.getString(it) },
                FloorPlanEntry.listOf(o.optJSONArray("plans")))
        }

        /** [[lat, lon], …] as in the doc */
        fun ringJson(r: List<LatLon>) = JSONArray().apply { r.forEach { put(JSONArray().put(it.lat).put(it.lon)) } }
        fun ring(a: JSONArray?): List<LatLon> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            a!!.optJSONArray(i)?.let { p -> LatLon(p.getDouble(0), p.getDouble(1)) } }
        fun rings(a: JSONArray?): List<List<LatLon>> = (0 until (a?.length() ?: 0)).map { ring(a!!.getJSONArray(it)) }.filter { it.size >= 3 }

        /** Rough area (deg²): enough to pick the largest ring. */
        fun ringArea(r: List<LatLon>): Double {
            var s = 0.0
            for (i in r.indices) { val a = r[i]; val b = r[(i + 1) % r.size]; s += a.lon * b.lat - b.lon * a.lat }
            return kotlin.math.abs(s) / 2
        }
    }
}

/** Overpass queries and parsing for indoor mode (the HTTP call is [io.github.tewnut.poicapture.net.OverpassClient]). */
object BuildingQuery {
    private fun f6(d: Double) = String.format(java.util.Locale.ROOT, "%.6f", d)
    const val NEAR_M = 150

    /**
     * Buildings containing the point (is_in: large malls whose walls are far away) or within [NEAR_M] of it. The
     * area pivot needs Overpass's areas, which not every building has: the `around` part catches the others. A mall or
     * market mapped only as an area (`shop=mall` / `amenity=marketplace`, no `building=*`) counts as the building too.
     */
    fun buildingsQuery(p: LatLon, radius: Int = NEAR_M): String {
        val ll = "${f6(p.lat)},${f6(p.lon)}"
        val sb = StringBuilder("[out:json][timeout:25];is_in($ll)->.a;(")
        for (f in AREA_FILTERS) sb.append("way(pivot.a)$f;relation(pivot.a)$f;")
        for (f in AREA_FILTERS) sb.append("way(around:$radius,$ll)$f;relation(around:$radius,$ll)$f[type=multipolygon];")
        return sb.append(");out tags geom;").toString()
    }

    /** What makes an OSM area "the building" of an indoor walk. */
    private val AREA_FILTERS = listOf("[building]", "[shop=mall]", "[amenity=marketplace]")
    fun isBuildingLike(tags: JSONObject) = tags.has("building") || tags.optString("shop") == "mall" ||
        tags.optString("amenity") == "marketplace"

    /** How far the name search looks around the walker / the map centre. */
    const val SEARCH_M = 30_000

    /**
     * "Tìm theo tên": buildings, malls and markets whose name contains [text] (case- and accent-tolerant, see
     * [nameRegex]) within [radiusM] of [p] (a box: much cheaper than `around` for Overpass). Nodes too (a mall mapped
     * as a point): picking one looks the building up at that point.
     */
    fun nameSearchQuery(text: String, p: LatLon, radiusM: Int = SEARCH_M): String {
        val re = nameRegex(text)
        val dLat = radiusM / 111_320.0
        val dLon = dLat / kotlin.math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)
        val box = "${f6(p.lat - dLat)},${f6(p.lon - dLon)},${f6(p.lat + dLat)},${f6(p.lon + dLon)}"
        val sb = StringBuilder("[out:json][timeout:25];(")
        for (f in AREA_FILTERS) sb.append("way$f[name~\"$re\",i]($box);relation$f[name~\"$re\",i]($box);")
        sb.append("node[shop=mall][name~\"$re\",i]($box);node[amenity=marketplace][name~\"$re\",i]($box);")
        return sb.append(");out tags geom;").toString()
    }

    private val VARIANTS: Map<Char, String> = mapOf(
        'a' to "aàáảãạăằắẳẵặâầấẩẫậ", 'e' to "eèéẻẽẹêềếểễệ", 'i' to "iìíỉĩị", 'o' to "oòóỏõọôồốổỗộơờớởỡợ",
        'u' to "uùúủũụưừứửữự", 'y' to "yỳýỷỹỵ", 'd' to "dđ")

    /** Lower case, accents removed (đ → d): "Vạn Hạnh" → "van hanh". */
    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(java.util.Locale.ROOT).replace('đ', 'd'),
        java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /**
     * An Overpass regex matching [text] with or without Vietnamese accents: every vowel (and d) becomes an alternation
     * of its accented forms in both cases (`(a|à|á|…|A|À|…)`; alternations, not bracket classes, so it works whatever
     * the server's regex library does with multi-byte characters), spaces match any run of spaces, punctuation any character.
     */
    fun nameRegex(text: String): String {
        val sb = StringBuilder()
        var space = false
        for (c in fold(text.trim())) {
            if (c.isWhitespace()) { if (!space) sb.append(" +"); space = true; continue }
            space = false
            val v = VARIANTS[c]
            when {
                v != null -> sb.append('(').append((v.toList() + v.uppercase().toList()).distinct().joinToString("|")).append(')')
                c.isLetterOrDigit() -> sb.append(c)
                else -> sb.append('.')   // punctuation: any character ("AEON-Mall" finds "Aeon Mall"), nothing to escape
            }
        }
        return sb.toString()
    }

    /** One answer of the name search. [building] is null for a node (looked up at [at] when picked). */
    data class Hit(val osm: String, val name: String, val kind: String, val at: LatLon, val distanceM: Double, val building: Building?)

    /** The name search's answer, nearest first, at most [max]. */
    fun parseSearch(body: String, from: LatLon, max: Int = 20): List<Hit> {
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val buildings = parseBuildings(body).associateBy { it.osm }
        val out = mutableListOf<Hit>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val name = tags.optString("name").ifBlank { null } ?: continue
            val id = "${e.optString("type")}/${e.optLong("id")}"
            val kind = when {
                tags.optString("shop") == "mall" -> "mall"
                tags.optString("amenity") == "marketplace" -> "marketplace"
                else -> "building"
            }
            val b = buildings[id]
            val at = when {
                b != null -> b.center
                e.optString("type") == "node" && e.has("lat") -> LatLon(e.getDouble("lat"), e.getDouble("lon"))
                else -> continue
            }
            out += Hit(id, name, kind, at, b?.distanceM(from)?.takeIf { it < Double.MAX_VALUE } ?: Geo.distanceM(from, at), b)
        }
        return out.distinctBy { it.osm }.sortedBy { it.distanceM }.take(max)
    }

    /** What is mapped inside a building's box: indoor rooms / areas / corridors, shops and amenities. */
    fun featuresQuery(b: Building): String {
        val all = b.outer.flatten()
        val box = "${f6(all.minOf { it.lat })},${f6(all.minOf { it.lon })},${f6(all.maxOf { it.lat })},${f6(all.maxOf { it.lon })}"
        return "[out:json][timeout:25];(nwr[indoor~\"^(room|area|corridor)$\"]($box);nwr[shop]($box);nwr[amenity][level]($box);" +
            "nwr[office][level]($box););out tags geom;"
    }

    /** All buildings in an Overpass answer (ways and multipolygon relations, rings assembled). */
    fun parseBuildings(body: String): List<Building> {
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = LinkedHashMap<String, Building>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            if (!isBuildingLike(tags)) continue
            val type = e.optString("type")
            val (outer, holes) = when (type) {
                "way" -> listOf(geom(e.optJSONArray("geometry"))).filter { it.size >= 3 } to emptyList()
                "relation" -> {
                    val m = e.optJSONArray("members") ?: JSONArray()
                    val parts = (0 until m.length()).map { m.getJSONObject(it) }.filter { it.optString("type") == "way" }
                    assemble(parts.filter { it.optString("role") != "inner" }.map { geom(it.optJSONArray("geometry")) }) to
                        assemble(parts.filter { it.optString("role") == "inner" }.map { geom(it.optJSONArray("geometry")) })
                }
                else -> continue
            }
            if (outer.isEmpty()) continue
            fun int(k: String) = tags.optString(k).trim().toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
            val addr = tags.keys().asSequence().filter { it.startsWith("addr:") }.associateWith { tags.getString(it) }
            val id = "$type/${e.getLong("id")}"
            out[id] = Building(id, tags.optString("name").ifBlank { null }, int("building:levels"),
                int("building:levels:underground"), outer, holes, addr,
                tags.optString("min_level").ifBlank { null }, tags.optString("max_level").ifBlank { null })
        }
        return out.values.toList()
    }

    /** The building at p: the smallest containing it, else the nearest within [NEAR_M]. */
    fun pick(buildings: List<Building>, p: LatLon, maxM: Double = NEAR_M.toDouble()): Building? =
        buildings.filter { it.contains(p) }.minByOrNull { Building.ringArea(it.footprint) }
            ?: buildings.map { it to it.distanceM(p) }.filter { it.second <= maxM }.minByOrNull { it.second }?.first

    /** Indoor features and shops inside the building's footprint. */
    fun parseFeatures(body: String, b: Building): List<IndoorFeature> {
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<IndoorFeature>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val type = e.optString("type")
            val g = when (type) {
                "node" -> listOf(LatLon(e.optDouble("lat"), e.optDouble("lon")))
                "way" -> geom(e.optJSONArray("geometry"))
                else -> continue   // relations inside a mall are rare; their members come as ways anyway
            }
            if (g.isEmpty() || g.any { it.lat.isNaN() }) continue
            val c = LatLon(g.map { it.lat }.average(), g.map { it.lon }.average())
            if (!b.contains(c)) continue
            val kind = when {
                tags.has("shop") -> "shop"
                tags.has("amenity") || tags.has("office") -> "shop"
                else -> tags.optString("indoor")
            }
            val tag = listOf("shop", "amenity", "office").firstOrNull { tags.has(it) }?.let { "$it=${tags.getString(it)}" }
            out += IndoorFeature("$type/${e.getLong("id")}", kind, tags.optString("name").ifBlank { null },
                tags.optString("level").ifBlank { tags.optString("level:ref").ifBlank { null } },
                tags.optString("addr:unit").ifBlank { tags.optString("ref").ifBlank { null } }, tag, g)
        }
        return out
    }

    private fun geom(a: JSONArray?): List<LatLon> = (0 until (a?.length() ?: 0)).map { i ->
        a!!.getJSONObject(i).let { LatLon(it.getDouble("lat"), it.getDouble("lon")) } }

    /** Join the member ways of a multipolygon into closed rings (open ends matched head to tail, either way round). */
    fun assemble(parts: List<List<LatLon>>): List<List<LatLon>> {
        val open = parts.filter { it.size >= 2 }.toMutableList()
        val rings = mutableListOf<List<LatLon>>()
        while (open.isNotEmpty()) {
            var ring = open.removeAt(0).toMutableList()
            var grown = true
            while (ring.first() != ring.last() && grown) {
                grown = false
                val end = ring.last()
                val k = open.indexOfFirst { it.first() == end || it.last() == end }
                if (k >= 0) {
                    val w = open.removeAt(k)
                    ring.addAll((if (w.first() == end) w else w.reversed()).drop(1))
                    grown = true
                }
            }
            if (ring.size >= 4 && ring.first() == ring.last()) ring = ring.dropLast(1).toMutableList()
            if (ring.size >= 3) rings += ring
        }
        return rings
    }
}

/**
 * The buildings found lately (setup, indoor), kept on disk: the building card shows the one here at once, offline
 * too, while Overpass is asked again. The last [max] buildings picked, newest first, without their floor plans.
 */
class BuildingCache(private val file: java.io.File, private val max: Int = 30) {
    @Synchronized fun all(): List<Building> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { Building.fromJson(a.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** The cached building at [p] (containing it, else the nearest within [maxM]). */
    fun near(p: LatLon, maxM: Double = BuildingQuery.NEAR_M.toDouble()): Building? = BuildingQuery.pick(all(), p, maxM)

    @Synchronized fun put(b: Building) {
        val list = (listOf(b.copy(plans = emptyList())) + all().filter { it.osm != b.osm }).take(max)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.path + ".tmp")
            tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}

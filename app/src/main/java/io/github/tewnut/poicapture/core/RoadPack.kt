package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Offline road data for a region ("gói dữ liệu đường"), built from OpenStreetMap (Geofabrik) by
 * tools/roadpack/build_roadpacks.py. A ZIP with:
 *   pack.json             header (magic, format, region, bbox, cell size, road filter, source, attribution, dates)
 *   cells/{x}_{y}.json    {"w": [[id, piece, name, ref, highway, oneway, [lat0e6, lon0e6, dlat, dlon, …]], …]}
 * Cells are the app's RoadCells grid (0.005°). Long ways are cut into pieces of about one cell (same OSM id, piece
 * 0, 1, …), each stored in the cell where it starts; a piece may end in the next cell. Readers merge by (id, piece).
 */
class RoadPack(val file: File) : AutoCloseable {
    val header: JSONObject
    private val zip: ZipFile = ZipFile(file)
    val id: String
    val name: String
    val south: Double; val west: Double; val north: Double; val east: Double
    /**
     * Format 2 (a province): the cells the pack covers, per row y the inclusive x ranges. Null for format 1 (a box: every
     * cell of the bbox). A province's bbox overlaps its neighbours, so only the listed cells are "covered".
     */
    private val coverage: Map<Int, IntArray>?

    init {
        try {
            header = JSONObject(zip.getInputStream(zip.getEntry("pack.json") ?: throw IOException("pack.json missing")).bufferedReader().readText())
            if (header.optString("magic") != MAGIC) throw IOException("not a road data pack")
            if (header.optInt("format") !in 1..FORMAT) throw IOException("pack format ${header.optInt("format")} not supported (max $FORMAT)")
            if (header.optDouble("cellSize") != RoadCells.SIZE) throw IOException("the pack's cell size differs from the app's")
            if (header.optString("skip") != Overpass.SKIP_AREA) throw IOException("the pack uses a different road filter than the app")
            id = header.getString("id")
            name = header.optString("name", id)
            val b = header.getJSONArray("bbox")
            south = b.getDouble(0); west = b.getDouble(1); north = b.getDouble(2); east = b.getDouble(3)
            coverage = header.optJSONArray("coverage")?.let { rows ->
                HashMap<Int, IntArray>(rows.length() * 2).also { m ->
                    for (i in 0 until rows.length()) {
                        val r = rows.getJSONArray(i)
                        m[r.getInt(0)] = IntArray(r.length() - 1) { k -> r.getInt(k + 1) }
                    }
                }
            }
        } catch (e: Exception) {
            zip.close()
            throw if (e is IOException) e else IOException("broken pack: ${e.message}")
        }
    }

    val version: String get() = header.optString("version")
    val attribution: String get() = header.optString("attribution")
    val source: String get() = "pack:$id@$version"

    fun covers(c: RoadCells.Cell): Boolean {
        val cov = coverage ?: return !(c.north <= south || c.south >= north || c.east <= west || c.west >= east)
        val r = cov[c.y] ?: return false
        var i = 0
        while (i + 1 < r.size) { if (c.x in r[i]..r[i + 1]) return true; i += 2 }
        return false
    }

    /** Ways (pieces) of one cell; empty when the pack covers the cell but it has no road. */
    @Synchronized
    fun ways(c: RoadCells.Cell): List<Way> {
        val e = zip.getEntry("cells/${c.x}_${c.y}.json") ?: return emptyList()
        val w = JSONObject(zip.getInputStream(e).bufferedReader().readText()).getJSONArray("w")
        return (0 until w.length()).map { decode(w.getJSONArray(it)) }
    }

    override fun close() = zip.close()

    companion object {
        const val MAGIC = "road-capture-roadpack"   // from the app's first name; published packs carry it, keep it
        const val FORMAT = 2   // 1 = a box, 2 = an administrative area with its list of covered cells

        fun decode(a: JSONArray): Way {
            val c = a.getJSONArray(6)
            val pts = ArrayList<LatLon>(c.length() / 2)
            var lat = 0L; var lon = 0L
            var i = 0
            while (i + 1 < c.length()) {
                lat += c.getLong(i); lon += c.getLong(i + 1)
                pts += LatLon(lat / 1e6, lon / 1e6)
                i += 2
            }
            return Way(a.getLong(0), a.optString(2), a.optString(3), a.optString(4), a.optString(5), pts, piece = a.getInt(1))
        }

        /** Encode a way piece the way the builder does (used by tests and to keep both sides in sync). */
        fun encode(w: Way): JSONArray {
            val c = JSONArray()
            var lat = 0L; var lon = 0L
            for (p in w.geometry) {
                val la = Math.round(p.lat * 1e6); val lo = Math.round(p.lon * 1e6)
                c.put(la - lat); c.put(lo - lon); lat = la; lon = lo
            }
            return JSONArray().put(w.id).put(w.piece).put(w.name).put(w.ref).put(w.highway).put(w.oneway).put(c)
        }
    }
}

/** Installed packs (zip files in filesDir/roadpacks); open zips are kept and reused. */
class RoadPacks(val dir: File) {
    private val open = LinkedHashMap<String, RoadPack>()

    @Synchronized
    fun installed(): List<RoadPack> {
        dir.mkdirs()
        val files = dir.listFiles { f -> f.name.endsWith(".zip") }?.toList() ?: emptyList()
        for (f in files) if (f.path !in open) runCatching { RoadPack(f) }.getOrNull()?.let { open[f.path] = it }
        open.keys.filter { k -> files.none { it.path == k } }.forEach { open.remove(it)?.close() }
        return open.values.toList()
    }

    /** The first installed pack covering the cell, or null when none does. */
    fun forCell(c: RoadCells.Cell): RoadPack? = installed().firstOrNull { it.covers(c) }

    /**
     * Install a downloaded / imported pack: it must open and validate, then replaces any pack with the same id
     * (atomic rename). Returns the installed pack.
     */
    @Synchronized
    fun install(tmp: File): RoadPack {
        val id = RoadPack(tmp).use { it.id }
        dir.mkdirs()
        val dst = File(dir, "$id.zip")
        open.remove(dst.path)?.close()
        if (dst.exists() && !dst.delete()) throw IOException("could not replace the old pack")
        if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
        return RoadPack(dst).also { open[dst.path] = it }
    }

    @Synchronized
    fun delete(id: String) {
        val f = File(dir, "$id.zip")
        open.remove(f.path)?.close()
        f.delete()
    }
}

/** Overpass answers per cell kept on disk (filesDir/roadcache), so an area loaded once works offline later. */
class CellCache(val dir: File, private val maxAgeMs: Long = 30L * 24 * 3600_000, private val maxBytes: Long = 50L * 1024 * 1024) {
    private fun file(c: RoadCells.Cell) = File(dir, "${c.x}_${c.y}_f$FILTER_VERSION.json")

    fun get(c: RoadCells.Cell, nowMs: Long = System.currentTimeMillis()): List<Way>? {
        val f = file(c)
        if (!f.exists() || nowMs - f.lastModified() > maxAgeMs) return null
        return runCatching {
            val a = JSONObject(f.readText()).getJSONArray("w")
            (0 until a.length()).map { RoadPack.decode(a.getJSONArray(it)) }
        }.getOrNull()
    }

    @Synchronized
    fun put(c: RoadCells.Cell, ways: List<Way>) {
        dir.mkdirs()
        file(c).writeText(JSONObject().put("w", JSONArray(ways.map { RoadPack.encode(it) })).toString())
        // keep the cache bounded: drop the oldest files
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= maxBytes) break; total -= f.length(); f.delete() }
    }

    companion object { val FILTER_VERSION = Overpass.SKIP_AREA.hashCode().toUInt().toString(16) }
}

/** Where road data for a cell comes from, in order: an installed pack, the disk cache, then (caller) Overpass. */
object RoadData {
    data class Hit(val ways: List<Way>, val source: String)

    fun local(c: RoadCells.Cell, packs: RoadPacks, cache: CellCache): Hit? {
        packs.forCell(c)?.let { return Hit(it.ways(c), it.source) }   // covered: even an empty cell is final
        cache.get(c)?.let { return Hit(it, "cache") }
        return null
    }
}

/** Why the pack catalogue (index.json) could not be fetched; the UI words it in the user's language. */
enum class CatalogueError {
    /** nothing published at that address yet */
    NOT_PUBLISHED,
    /** 401 / 403: no access */
    FORBIDDEN,
    /** 5xx: the server is failing, try later */
    SERVER,
    /** no network or unknown host */
    OFFLINE,
    /** the server did not answer in time */
    TIMEOUT,
    /** any other HTTP status */
    OTHER;

    companion object {
        /**
         * GitHub answers a missing release asset with 504, not 404, so on a GitHub release URL a 504 nearly always means
         * "nothing published there yet" rather than a real gateway timeout.
         */
        fun forHttp(code: Int, url: String): CatalogueError {
            val githubRelease = url.contains("github.com/") && url.contains("/releases/download/")
            return when {
                code == 404 || code == 410 || (code == 504 && githubRelease) -> NOT_PUBLISHED
                code == 401 || code == 403 -> FORBIDDEN
                code >= 500 -> SERVER
                else -> OTHER
            }
        }
    }
}

/** The catalogue fetch failed because of [error]; [code] is the HTTP status when there was one. */
class CatalogueException(val error: CatalogueError, val code: Int? = null, detail: String? = null) :
    IOException("catalogue: $error${code?.let { " (HTTP $it)" } ?: ""}${detail?.let { ": $it" } ?: ""}")

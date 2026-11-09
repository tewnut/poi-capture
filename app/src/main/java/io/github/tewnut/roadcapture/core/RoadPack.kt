package io.github.tewnut.roadcapture.core

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

    init {
        try {
            header = JSONObject(zip.getInputStream(zip.getEntry("pack.json") ?: throw IOException("thiếu pack.json")).bufferedReader().readText())
            if (header.optString("magic") != MAGIC) throw IOException("không phải gói dữ liệu đường")
            if (header.optInt("format") != FORMAT) throw IOException("định dạng gói ${header.optInt("format")} không hỗ trợ (cần $FORMAT)")
            if (header.optDouble("cellSize") != RoadCells.SIZE) throw IOException("kích thước ô của gói khác với app")
            if (header.optString("skip") != Overpass.SKIP_AREA) throw IOException("gói dùng bộ lọc loại đường khác với app")
            id = header.getString("id")
            name = header.optString("name", id)
            val b = header.getJSONArray("bbox")
            south = b.getDouble(0); west = b.getDouble(1); north = b.getDouble(2); east = b.getDouble(3)
        } catch (e: Exception) {
            zip.close()
            throw if (e is IOException) e else IOException("gói hỏng: ${e.message}")
        }
    }

    val version: String get() = header.optString("version")
    val attribution: String get() = header.optString("attribution")
    val source: String get() = "pack:$id@$version"

    fun covers(c: RoadCells.Cell): Boolean = !(c.north <= south || c.south >= north || c.east <= west || c.west >= east)

    /** Ways (pieces) of one cell; empty when the pack covers the cell but it has no road. */
    @Synchronized
    fun ways(c: RoadCells.Cell): List<Way> {
        val e = zip.getEntry("cells/${c.x}_${c.y}.json") ?: return emptyList()
        val w = JSONObject(zip.getInputStream(e).bufferedReader().readText()).getJSONArray("w")
        return (0 until w.length()).map { decode(w.getJSONArray(it)) }
    }

    override fun close() = zip.close()

    companion object {
        const val MAGIC = "road-capture-roadpack"
        const val FORMAT = 1

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
        if (dst.exists() && !dst.delete()) throw IOException("không thay được gói cũ")
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

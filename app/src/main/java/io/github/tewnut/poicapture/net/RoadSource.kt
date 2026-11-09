package io.github.tewnut.poicapture.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import io.github.tewnut.poicapture.core.CellCache
import io.github.tewnut.poicapture.core.Onboarding
import io.github.tewnut.poicapture.core.RoadCells
import io.github.tewnut.poicapture.core.RoadData
import io.github.tewnut.poicapture.core.RoadPacks
import java.io.File

/** App-wide road data: installed offline packs, the disk cache of Overpass cells, the last downloaded pack catalogue. */
object RoadSource {
    @Volatile private var packs: RoadPacks? = null
    @Volatile private var cache: CellCache? = null

    fun packs(ctx: Context): RoadPacks = packs ?: synchronized(this) {
        packs ?: RoadPacks(File(ctx.applicationContext.filesDir, "roadpacks")).also { packs = it }
    }

    fun cache(ctx: Context): CellCache = cache ?: synchronized(this) {
        cache ?: CellCache(File(ctx.applicationContext.filesDir, "roadcache")).also { cache = it }
    }

    /** Pack (even an empty cell inside a pack), else disk cache; null = ask Overpass. */
    fun local(ctx: Context, c: RoadCells.Cell): RoadData.Hit? = RoadData.local(c, packs(ctx), cache(ctx))

    // ---------- catalogue (index.json of downloadable packs) ----------

    data class Entry(val id: String, val name: String, val version: String, val bytes: Long, val sha256: String,
                     val url: String, val south: Double, val west: Double, val north: Double, val east: Double) {
        fun covers(lat: Double, lon: Double) = lat in south..north && lon in west..east
    }

    private fun catalogueFile(ctx: Context) = File(ctx.filesDir, "roadpacks-index.json")

    fun parseCatalogue(body: String, baseUrl: String): List<Entry> {
        val a: JSONArray = JSONObject(body).optJSONArray("packs") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val b = o.getJSONArray("bbox")
            Entry(o.getString("id"), o.optString("name"), o.optString("version"), o.optLong("bytes"), o.optString("sha256"),
                java.net.URL(java.net.URL(baseUrl), o.getString("url")).toString(),
                b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
        }
    }

    /** The pack to suggest at a position: the smallest region covering it. */
    fun suggest(list: List<Entry>, lat: Double, lon: Double): Entry? =
        Onboarding.suggest(list, lat, lon) { Onboarding.Box(it.south, it.west, it.north, it.east) }

    /** The last catalogue fetched (kept so "Ghi mới" can suggest a pack offline). */
    fun cachedCatalogue(ctx: Context): List<Entry> = runCatching {
        val o = JSONObject(catalogueFile(ctx).readText())
        parseCatalogue(o.getString("body"), o.getString("url"))
    }.getOrDefault(emptyList())

    fun saveCatalogue(ctx: Context, url: String, body: String) {
        catalogueFile(ctx).writeText(JSONObject().put("url", url).put("body", body).toString())
    }
}

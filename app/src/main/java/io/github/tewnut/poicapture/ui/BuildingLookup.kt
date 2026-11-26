package io.github.tewnut.poicapture.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tewnut.poicapture.core.Building
import io.github.tewnut.poicapture.core.BuildingCache
import io.github.tewnut.poicapture.core.BuildingQuery
import io.github.tewnut.poicapture.core.Geo
import io.github.tewnut.poicapture.core.LatLon
import io.github.tewnut.poicapture.net.OverpassClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Where the building lookup stands, as the building card says it. */
enum class LookupStatus { WAITING_FIX, SEARCHING, FOUND, NONE_FOUND, OFFLINE }

private const val TAG = "QuetPho"
private fun f4(d: Double) = String.format(Locale.ROOT, "%.4f", d)
private fun r4(d: Double) = Math.round(d * 1e4) / 1e4

/** The phone's last known position from any provider, however old (marked stale when older than a minute). */
@SuppressLint("MissingPermission")
fun lastKnownFix(ctx: Context): LiveLocation? = runCatching {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    lm.allProviders.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.elapsedRealtimeNanos }?.let { l ->
            val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
            LiveLocation(l.latitude, l.longitude, l.accuracy, false, System.currentTimeMillis(), gps = l.provider == LocationManager.GPS_PROVIDER,
                stale = ageMs > 60_000, fixNanos = l.elapsedRealtimeNanos, provider = (l.provider ?: "") + "-last")
        }
}.getOrNull()

/**
 * The building of an indoor walk at setup (docs/indoor.md §4): looked up as soon as there is any position (a stale one
 * too), again when a better fix comes (moved > 100 m, or much more accurate), or where the walker taps the map / picks a
 * name. The last buildings are cached on disk (shown at once, offline too). Every lookup is logged (Log.i "QuetPho")
 * and kept as an indoor.jsonl `lookup` line for the walk ([flushTo]).
 */
class BuildingLookupState(private val cache: BuildingCache) {
    var building by mutableStateOf<Building?>(null)
    var status by mutableStateOf(LookupStatus.WAITING_FIX)
    /** the walker chose to go without a building */
    var none by mutableStateOf(false)
    /** "Chọn trên bản đồ": the card asks for a tap on the map */
    var pickOnMap by mutableStateOf(false)
    internal var request by mutableStateOf<Request?>(null)
    internal var tick by mutableIntStateOf(0)
    private val lines = mutableListOf<JSONObject>()

    /** One lookup: where, how far around, why ("auto" | "tap" | "name"), from which fix. */
    internal data class Request(val at: LatLon, val maxM: Double, val how: String, val fix: LiveLocation?)

    /** Look at [p] (a tap on the map, a name picked). */
    fun at(p: LatLon, how: String) {
        none = false; pickOnMap = false
        request = Request(p, if (how == "tap") 30.0 else BuildingQuery.NEAR_M.toDouble(), how, null)
    }

    fun retry() { none = false; tick++ }
    fun chooseNone() { none = true; pickOnMap = false }

    /** A name search answer picked: its building directly, or (a point) the building there. */
    fun pick(h: BuildingQuery.Hit) {
        log(JSONObject().put("type", "lookup").put("how", "name").put("found", 1).put("picked", h.osm))
        val b = h.building
        if (b == null) { at(h.at, "name"); return }
        none = false; pickOnMap = false
        building = b; status = LookupStatus.FOUND
        request = Request(b.center, 0.0, "name-picked", null)   // loads what is mapped inside (see the effect)
    }

    @Synchronized fun log(o: JSONObject) {
        if (!o.has("t")) o.put("t", SystemClock.elapsedRealtimeNanos())
        lines += o
    }

    /** The lookups so far, as the first lines of the new walk's indoor.jsonl. */
    @Synchronized fun flushTo(f: File) {
        if (lines.isEmpty()) return
        runCatching { f.parentFile?.mkdirs(); f.appendText(lines.joinToString("") { it.toString() + "\n" }) }
        lines.clear()
    }

    internal fun cached(p: LatLon, maxM: Double) = runCatching { cache.near(p, maxM) }.getOrNull()
    internal fun store(b: Building) = runCatching { cache.put(b) }
}

@Composable
fun rememberBuildingLookup(ctx: Context, overpassUrl: String, indoor: Boolean, fix: LiveLocation?): BuildingLookupState {
    val st = remember { BuildingLookupState(BuildingCache(File(ctx.applicationContext.filesDir, "buildings_cache.json"))) }
    // automatic lookups follow the position until the walker picks a building by hand
    LaunchedEffect(indoor, fix) {
        if (!indoor || fix == null || st.none) return@LaunchedEffect
        val cur = st.request
        if (cur != null && cur.how != "auto") return@LaunchedEffect
        val prev = cur?.fix
        val moved = prev?.let { Geo.distanceM(LatLon(it.lat, it.lon), LatLon(fix.lat, fix.lon)) } ?: Double.MAX_VALUE
        val better = prev != null && (moved > 100 ||
            (fix.accuracyM < prev.accuracyM / 2 && prev.accuracyM > 30) ||
            (prev.stale && !fix.stale && moved > 30))
        if (cur == null || better) {
            if (cur != null) Log.i(TAG, "building: better fix (moved ${moved.toInt()} m, ±${prev?.accuracyM?.toInt()} → ±${fix.accuracyM.toInt()} m): again")
            st.request = BuildingLookupState.Request(LatLon(fix.lat, fix.lon), BuildingQuery.NEAR_M.toDouble(), "auto", fix)
        }
    }
    LaunchedEffect(indoor, fix == null) {
        if (indoor && fix == null && st.request == null && st.building == null) {
            st.status = LookupStatus.WAITING_FIX
            Log.i(TAG, "building: waiting for a position")
        }
    }
    LaunchedEffect(st.request, st.tick, indoor) {
        val r = st.request ?: return@LaunchedEffect
        if (!indoor) return@LaunchedEffect
        if (r.how == "name-picked") { st.building?.let { loadFeatures(st, overpassUrl, it) }; return@LaunchedEffect }
        val fixJson = r.fix?.let { f ->
            JSONObject().put("lat", r4(f.lat)).put("lon", r4(f.lon)).put("accM", Math.round(f.accuracyM * 10) / 10.0)
                .put("provider", f.provider.ifEmpty { if (f.gps) "gps" else "network" })
                .put("ageS", (SystemClock.elapsedRealtimeNanos() - f.fixNanos) / 1_000_000_000L)
        }
        Log.i(TAG, "building: lookup ${r.how} at ${f4(r.at.lat)},${f4(r.at.lon)} within ${r.maxM.toInt()} m" +
            (r.fix?.let { " (fix ±${it.accuracyM.toInt()} m ${it.provider}${if (it.stale) ", stale" else ""})" } ?: ""))
        // the cached building here first: shown at once, the answer replaces it
        val hit = withContext(Dispatchers.IO) { st.cached(r.at, r.maxM) }
        if (hit != null && (st.building == null || r.how != "auto")) {
            st.building = hit; st.status = LookupStatus.FOUND
            Log.i(TAG, "building: cached ${hit.osm} \"${hit.name ?: ""}\"")
        } else if (st.building == null || r.how != "auto") st.status = LookupStatus.SEARCHING
        val trace = mutableListOf<OverpassClient.Attempt>()
        val t0 = SystemClock.elapsedRealtime()
        val line = JSONObject().put("type", "lookup").put("how", r.how).put("fix", fixJson ?: JSONObject.NULL)
            .put("at", org.json.JSONArray().put(r4(r.at.lat)).put(r4(r.at.lon))).put("maxM", r.maxM.toInt())
        try {
            val body = OverpassClient.body(overpassUrl, BuildingQuery.buildingsQuery(r.at), "buildings", trace, readTimeoutMs = 25_000)
            val all = withContext(Dispatchers.Default) { BuildingQuery.parseBuildings(body) }
            val b = BuildingQuery.pick(all, r.at, r.maxM)
            Log.i(TAG, "building: ${all.size} buildings in the answer, picked ${b?.osm ?: "none"} \"${b?.name ?: ""}\"" +
                (b?.let { " (${it.distanceM(r.at).toInt()} m)" } ?: ""))
            st.log(line.put("ms", SystemClock.elapsedRealtime() - t0).put("http", trace.lastOrNull()?.http ?: JSONObject.NULL)
                .put("mirror", trace.lastOrNull()?.mirror ?: JSONObject.NULL).put("tries", trace.size)
                .put("found", all.size).put("picked", b?.osm ?: JSONObject.NULL).put("error", JSONObject.NULL))
            if (b == null) {
                if (st.building == null || r.how != "auto") { st.building = null; st.status = LookupStatus.NONE_FOUND }
                return@LaunchedEffect
            }
            st.building = b; st.status = LookupStatus.FOUND
            withContext(Dispatchers.IO) { st.store(b) }
            loadFeatures(st, overpassUrl, b)
        } catch (e: Exception) {
            val why = trace.lastOrNull()?.error ?: e.message ?: e.javaClass.simpleName
            Log.i(TAG, "building: lookup failed after ${trace.size} tries: $why")
            st.log(line.put("ms", SystemClock.elapsedRealtime() - t0).put("http", trace.lastOrNull()?.http ?: JSONObject.NULL)
                .put("mirror", trace.lastOrNull()?.mirror ?: JSONObject.NULL).put("tries", trace.size)
                .put("found", 0).put("picked", JSONObject.NULL).put("error", why.take(160)))
            if (st.building == null) st.status = LookupStatus.OFFLINE
        }
    }
    return st
}

/** What is already mapped inside: shown per level while walking (optional: the walk works without it). */
private suspend fun loadFeatures(st: BuildingLookupState, url: String, b: Building) {
    val features = runCatching {
        BuildingQuery.parseFeatures(OverpassClient.body(url, BuildingQuery.featuresQuery(b), "features"), b)
    }.getOrNull()
    Log.i(TAG, "building: ${features?.size ?: "no"} features inside ${b.osm}")
    if (features != null && st.building?.osm == b.osm) st.building = b.copy(features = features)
}

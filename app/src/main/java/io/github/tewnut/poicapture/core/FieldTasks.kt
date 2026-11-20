package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Work to do on site, from the POI Capture Reviewer ("Export" on the tasks list: GeoJSON, schema poi-tasks/1, see
 * docs/pipeline.md): visit a place (photos unreadable, a reviewer asked) or check whether a place is gone.
 * Shown on the setup map; done / skipped by the contributor, or done by a Place capture finished close to it.
 */
data class FieldTask(
    val id: String, val type: String, val name: String, val why: String, val at: LatLon,
    val place: String?, val session: String?,
    val state: String = OPEN, val doneAt: String? = null, val doneBy: String? = null,   // doneBy: "poi_… in s_…" or "contributor"
) {
    val open get() = state == OPEN
    val removalCheck get() = type == "verify_removal"

    companion object {
        const val OPEN = "open"; const val DONE = "done"; const val SKIPPED = "skipped"
    }
}

object FieldTasks {
    const val SCHEMA = "poi-tasks/1"
    /** A Place capture finished this close to an open task does it. */
    const val NEAR_M = 25.0

    class BadFile(msg: String) : Exception(msg)

    /** The tasks of an exported file. Throws [BadFile] when it is not one. */
    fun parse(text: String): List<FieldTask> {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: throw BadFile("not JSON")
        if (o.optString("type") != "FeatureCollection") throw BadFile("not GeoJSON")
        val schema = o.optString("schema")
        if (schema.isNotEmpty() && schema != SCHEMA) throw BadFile("schema $schema")
        val feats = o.optJSONArray("features") ?: JSONArray()
        val out = mutableListOf<FieldTask>()
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val c = f.optJSONObject("geometry")?.takeIf { it.optString("type") == "Point" }?.optJSONArray("coordinates") ?: continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val lon = c.optDouble(0); val lat = c.optDouble(1)
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val type = p.optString("type").takeIf { it == "visit" || it == "verify_removal" || it == "removal" } ?: continue
            out += FieldTask(
                id = p.optString("id").ifEmpty { "task-$i" }.take(200),
                type = if (type == "removal") "verify_removal" else type,
                name = p.optString("name").take(200), why = p.optString("why").take(500), at = LatLon(lat, lon),
                place = p.optString("place").takeIf { it.isNotEmpty() && it != "null" }, session = p.optString("session").takeIf { it.isNotEmpty() },
            )
        }
        if (out.isEmpty() && feats.length() > 0) throw BadFile("no tasks")
        return out.distinctBy { it.id }
    }

    /** A new export replaces the list; a task already done or skipped on this phone keeps that state. */
    fun merge(old: List<FieldTask>, new: List<FieldTask>): List<FieldTask> {
        val byId = old.associateBy { it.id }
        return new.map { n -> byId[n.id]?.takeIf { !it.open }?.let { n.copy(state = it.state, doneAt = it.doneAt, doneBy = it.doneBy) } ?: n }
    }

    /** The open task closest to [at], within [NEAR_M]. */
    fun nearestOpen(tasks: List<FieldTask>, at: LatLon, withinM: Double = NEAR_M): FieldTask? =
        tasks.filter { it.open }.map { it to Geo.distanceM(it.at, at) }.filter { it.second <= withinM }.minByOrNull { it.second }?.first

    fun toJson(t: FieldTask): JSONObject = JSONObject().put("id", t.id).put("type", t.type).put("name", t.name).put("why", t.why)
        .put("lat", t.at.lat).put("lon", t.at.lon).put("place", t.place ?: JSONObject.NULL).put("session", t.session ?: JSONObject.NULL)
        .put("state", t.state).put("doneAt", t.doneAt ?: JSONObject.NULL).put("doneBy", t.doneBy ?: JSONObject.NULL)

    fun fromJson(o: JSONObject) = FieldTask(o.getString("id"), o.getString("type"), o.optString("name"), o.optString("why"),
        LatLon(o.getDouble("lat"), o.getDouble("lon")), o.optString("place").takeIf { !o.isNull("place") && it.isNotEmpty() },
        o.optString("session").takeIf { !o.isNull("session") && it.isNotEmpty() }, o.optString("state", FieldTask.OPEN),
        o.optString("doneAt").takeIf { !o.isNull("doneAt") && it.isNotEmpty() }, o.optString("doneBy").takeIf { !o.isNull("doneBy") && it.isNotEmpty() })
}

/** The phone's task list: one file, filesDir/tasks.json. */
class FieldTaskStore(private val file: File) {
    @Synchronized fun all(): List<FieldTask> = runCatching {
        val a = JSONObject(file.readText()).getJSONArray("tasks")
        (0 until a.length()).map { FieldTasks.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized fun save(tasks: List<FieldTask>) {
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.writeText(JSONObject().put("schema", "poi-tasks-phone/1").put("tasks", JSONArray(tasks.map(FieldTasks::toJson))).toString())
        tmp.renameTo(file)
    }

    @Synchronized fun import(text: String): List<FieldTask> = FieldTasks.merge(all(), FieldTasks.parse(text)).also(::save)

    @Synchronized fun set(id: String, state: String, nowIso: String, by: String?) =
        save(all().map { if (it.id == id) it.copy(state = state, doneAt = if (state == FieldTask.OPEN) null else nowIso, doneBy = by) else it })

    @Synchronized fun clear() { file.delete() }
}

package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One floor plan of an indoor walk (docs/indoor.md §7), as kept in `session.building.plans` (or `session.indoor.plans`
 * without a building) and plans/plans.json.
 */
data class FloorPlanEntry(
    val level: String,                       // level name ("2", "B1")
    val photo: String?,                      // item id of the plan photo (null: imported from the gallery by the scanner)
    val file: String,                        // "plans/plan_2_1.jpg"
    val width: Int,
    val height: Int,
    val capture: String,                     // "mlkit-scanner" | "manual"
    val corners: List<Xy>? = null,           // manual only: the board on the (upright) photo, fractions, clockwise from top-left
    val toMap: PlanToMap? = null,            // null until placed
    val placedBy: String? = null,            // "footprint" | "drag" | "points" | "guess"
    val youAreHere: Xy? = null,              // plan px
    val t: Long = 0L,                        // elapsedRealtimeNanos of the plan photo
    val units: List<PlanUnit> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("level", level).put("photo", photo.orNull()).put("file", file)
        .put("size", JSONArray().put(width).put(height))
        .put("capture", capture)
        .put("corners", corners?.let { c -> JSONArray().apply { c.forEach { put(JSONArray().put(r4(it.x)).put(r4(it.y))) } } }.orNull())
        .put("toMap", toMap?.let { toMapJson(it) }.orNull())
        .put("placedBy", placedBy.orNull())
        .put("youAreHere", youAreHere?.let { JSONArray().put(it.x.toInt()).put(it.y.toInt()) }.orNull())
        .put("t", t)
        .put("units", JSONArray().apply { units.forEach { u ->
            put(JSONObject().put("unit", u.unit).put("x", u.x.toInt()).put("y", u.y.toInt()).put("h", u.h.toInt())) } })

    companion object {
        private fun r4(d: Double) = Math.round(d * 10000) / 10000.0

        fun toMapJson(m: PlanToMap): JSONObject = JSONObject()
            .put("origin", JSONArray().put(Math.round(m.origin.lat * 1e7) / 1e7).put(Math.round(m.origin.lon * 1e7) / 1e7))
            .put("scaleMPerPx", Math.round(m.scaleMPerPx * 1e6) / 1e6)
            .put("rotationDeg", Math.round(m.rotationDeg * 100) / 100.0)

        fun toMapOf(o: JSONObject?): PlanToMap? {
            val a = o?.optJSONArray("origin") ?: return null
            val s = o.optDouble("scaleMPerPx")
            if (s.isNaN() || s <= 0) return null
            return PlanToMap(LatLon(a.getDouble(0), a.getDouble(1)), s, o.optDouble("rotationDeg", 0.0))
        }

        private fun xy(a: JSONArray?): Xy? = a?.takeIf { it.length() >= 2 }?.let { Xy(it.getDouble(0), it.getDouble(1)) }

        fun fromJson(o: JSONObject): FloorPlanEntry {
            val size = o.optJSONArray("size")
            val c = o.optJSONArray("corners")
            val u = o.optJSONArray("units")
            return FloorPlanEntry(o.optString("level"), o.optStr("photo"), o.optString("file"),
                size?.optInt(0) ?: 0, size?.optInt(1) ?: 0, o.optString("capture", "manual"),
                c?.let { (0 until it.length()).mapNotNull { i -> xy(it.optJSONArray(i)) } }?.takeIf { it.size == 4 },
                toMapOf(o.optJSONObject("toMap")), o.optStr("placedBy"), xy(o.optJSONArray("youAreHere")), o.optLong("t"),
                (0 until (u?.length() ?: 0)).map { i -> u!!.getJSONObject(i).let {
                    PlanUnit(it.optString("unit"), it.optDouble("x"), it.optDouble("y"), it.optDouble("h", 0.0)) } })
        }

        fun listJson(plans: List<FloorPlanEntry>) = JSONArray().apply { plans.forEach { put(it.toJson()) } }
        fun listOf(a: JSONArray?): List<FloorPlanEntry> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            runCatching { fromJson(a!!.getJSONObject(i)) }.getOrNull() }

        /** "plans/plan_<level>_<n>.jpg" with the next free n for the level. */
        fun fileName(level: String, existing: List<FloorPlanEntry>): String {
            val n = existing.count { it.level == level } + 1
            return "plans/plan_${level}_$n.jpg"
        }
    }
}

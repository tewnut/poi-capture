package io.github.tewnut.poicapture.core

import org.json.JSONObject

/** What a session is for. */
enum class SessionKind(val json: String) {
    /** "Quét đường": ride along, the app photographs the row of buildings (auto photos / video). */
    SWEEP("sweep"),
    /** "Chụp địa điểm": stop at each place, photograph it on purpose (front, sign, side, inside, info). */
    POI("poi");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s } ?: SWEEP
    }
}

/** Status of a place as seen on site. */
enum class PoiStatus(val json: String) {
    NEW("new"), EXISTS("exists"), CLOSED("closed"), MOVED("moved");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s } ?: NEW
    }
}

object Pois {
    /** A new place, at the contributor's current position (refined later by a pin on the building). */
    fun new(id: String, nowMs: Long, at: Fix?): JSONObject = JSONObject()
        .put("id", id).put("createdAt", iso(nowMs)).put("tMs", nowMs)
        .put("position", if (at == null) JSONObject.NULL else JSONObject()
            .put("lat", at.lat).put("lon", at.lon).put("accuracyM", at.accuracyM).put("source", "gps"))
        .put("fields", JSONObject())
        .put("status", PoiStatus.NEW.json)

    /** Patch after the contributor reviewed the place: pin on the building, optional fields, status. */
    fun review(pin: LatLon?, pinFromGps: Boolean, fields: Map<String, String>, status: PoiStatus, nowMs: Long): JSONObject {
        val f = JSONObject()
        for ((k, v) in fields) if (v.isNotBlank()) f.put(k, v.trim())
        val p = JSONObject().put("fields", f).put("status", status.json).put("reviewedAt", iso(nowMs))
        if (pin != null) p.put("position", JSONObject().put("lat", pin.lat).put("lon", pin.lon)
            .put("source", if (pinFromGps) "gps" else "pin"))
        return p
    }
}

package io.github.tewnut.roadcapture.core

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

/** Role of a photo of a place (POI mode). */
enum class PhotoRole(val json: String, val label: String) {
    FACADE("facade", "Mặt tiền"),     // straight-on front: the cover photo
    SIGN("sign", "Biển hiệu"),        // the signboard, close: name / phone / category readable
    SIDE("side", "Bên hông"),
    INSIDE("inside", "Bên trong"),
    INFO("info", "Thông tin"),        // opening hours, menu, price list, phone number on the door
    CONTEXT("context", "Toàn cảnh");  // the place among its neighbours (helps find it again)

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s }
    }
}

/** Status of a place as seen on site. */
enum class PoiStatus(val json: String, val label: String) {
    NEW("new", "Mới"), EXISTS("exists", "Đã có trên bản đồ"), CLOSED("closed", "Đã đóng cửa"), MOVED("moved", "Đã chuyển đi");

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

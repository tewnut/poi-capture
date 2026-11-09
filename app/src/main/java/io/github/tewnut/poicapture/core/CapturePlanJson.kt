package io.github.tewnut.poicapture.core

import org.json.JSONObject

/** session.plan in the export (CapturePlan itself is shared with the iPhone app, without org.json). */
fun CapturePlan.toJson(): JSONObject = JSONObject()
    .put("facadeDistanceM", facadeDistanceM).put("hFovDeg", hFovDeg).put("coverageM", coverageM.round1())
    .put("photoEveryM", photoEveryM.round1()).put("maxSpeedKmh", maxSpeedKmh)
    .put("blurLimitKmh", blurLimitKmh).put("rateLimitKmh", rateLimitKmh)

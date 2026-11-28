package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

class Camera360CheckTest {
    // ---- pure rules ----

    @Test fun speedFromShotTimeAndSpacing() {
        assertEquals(14.4, Camera360Check.maxKmh(8.0, 2.0)!!, 1e-9)       // 8 m / 2 s = 4 m/s
        assertEquals(36.0, Camera360Check.maxKmh(20.0, 2.0)!!, 1e-9)
        assertNull(Camera360Check.maxKmh(8.0, 0.0))
    }

    @Test fun exifPositionWithin100m() {
        val sent = PhoneFix(10.776942, 106.700925, null, 4.0)
        val near = Camera360Check.exifResult(sent, ExifGps(10.7770, 106.7010, null))
        assertEquals(CheckOutcome.OK, near.outcome)
        assertTrue(near.distanceM!! < 100)
        val far = Camera360Check.exifResult(sent, ExifGps(10.7800, 106.7010, null))   // ~340 m north
        assertEquals(CheckOutcome.WARN, far.outcome)
        assertEquals(CheckFix.EXIF_FAR, far.fix)
        assertEquals(CheckFix.EXIF_NO_GPS, Camera360Check.exifResult(sent, ExifGps(null, null, null)).fix)
        assertEquals(CheckOutcome.WARN, Camera360Check.exifResult(sent, null).outcome)
        assertEquals(CheckFix.EXIF_NO_GPS, Camera360Check.exifResult(sent, ExifGps(0.0, 0.0, null)).fix)
    }

    @Test fun checkedTodayAndTicksStartOverEachDay() {
        val today = LocalDate.of(2026, 11, 28)
        assertTrue(Camera360Check.checkedToday("2026-11-28", today))
        assertFalse(Camera360Check.checkedToday("2026-11-27", today))
        assertFalse(Camera360Check.checkedToday("", today))
        assertEquals(setOf("card"), Camera360Check.ticksToday("2026-11-28", setOf("card"), today))
        assertEquals(emptySet<String>(), Camera360Check.ticksToday("2026-11-27", setOf("card"), today))
    }

    @Test fun manualListsPerModel() {
        val gopro = Camera360Check.manualItems(Camera360Model.GOPRO)
        assertEquals(listOf(ManualItem.LABS, ManualItem.SETUP_QR, ManualItem.CAMERA_GPS, ManualItem.CARD, ManualItem.BATTERY), gopro)
        assertEquals(4, Camera360Check.manualItems(Camera360Model.INSTA360).size)
        assertTrue(Camera360Check.manualItems(Camera360Model.THETA).isEmpty())
        val all = gopro.map { it.id }.toSet()
        assertTrue(Camera360Check.manualDone(Camera360Model.GOPRO, all))
        assertFalse(Camera360Check.manualDone(Camera360Model.GOPRO, all - "card"))
        assertFalse(Camera360Check.manualDone(Camera360Model.NONE, emptySet()))
    }

    @Test fun serialMatching() {
        assertTrue(Camera360Check.serialMatches("12345678", "12345678"))
        assertTrue(Camera360Check.serialMatches("THETAYR12345678.OSC", "YR12345678"))
        assertFalse(Camera360Check.serialMatches("12345678", "87654321"))
        assertFalse(Camera360Check.serialMatches("12345678", null))
        assertTrue(Camera360Check.isThetaX("RICOH THETA X"))
        assertFalse(Camera360Check.isThetaX("RICOH THETA Z1"))
    }

    @Test fun passedNeedsStepsOneToSix() {
        val ok = CheckStep.entries.map { CheckResult(it, CheckOutcome.OK) }
        assertTrue(Camera360Check.passed(ok))
        assertTrue(Camera360Check.passed(ok.map { if (it.step == CheckStep.EXIF_GPS) it.copy(outcome = CheckOutcome.WARN) else it }))
        assertFalse(Camera360Check.passed(ok.map { if (it.step == CheckStep.GPS) it.copy(outcome = CheckOutcome.FAIL) else it }))
    }

    // ---- the runner, against fakes ----

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 1, 2, 3)
    private val url = "http://192.168.1.1/files/abc/100RICOH/R0010001.JPG"

    /** A camera answering the OSC calls; [refuse]: command or option names it refuses. */
    private class FakeCamera(var maker: String = "RICOH", var serial: String = "12345678", var model: String = "RICOH THETA Z1",
                             val refuse: Set<String> = emptySet(), val pollsUntilDone: Int = 3) : OscTransport {
        val commands = mutableListOf<String>()
        private var polls = 0
        override fun exchange(method: String, path: String, body: String?, timeoutMs: Int): OscResponse {
            val b = body?.let { JSONObject(it) }
            return when (path) {
                "/osc/info" -> ok(JSONObject().put("manufacturer", maker).put("model", model).put("serialNumber", serial)
                    .put("apiLevel", org.json.JSONArray(listOf(2))))
                "/osc/state" -> ok(JSONObject().put("state", JSONObject().put("batteryLevel", 0.8)))
                "/osc/commands/status" -> if (++polls >= pollsUntilDone)
                    ok(JSONObject().put("state", "done").put("results", JSONObject().put("fileUrl", "http://192.168.1.1/files/abc/100RICOH/R0010001.JPG")))
                    else ok(JSONObject().put("state", "inProgress").put("id", "7"))
                else -> {
                    val name = b!!.getString("name")
                    val key = if (name == "camera.setOptions") b.getJSONObject("parameters").getJSONObject("options").keys().next() else name
                    commands += key
                    if (key in refuse) ok(JSONObject().put("state", "error").put("error", JSONObject().put("code", "invalidParameterValue").put("message", "no")))
                    else if (name == "camera.takePicture") ok(JSONObject().put("state", "inProgress").put("id", "7"))
                    else ok(JSONObject().put("name", name).put("state", "done"))
                }
            }
        }
        private fun ok(o: JSONObject) = OscResponse(200, o.toString())
    }

    private class FakeLink(val cam: FakeCamera, val files: OscFiles, val connects: Boolean = true, override val clockSet: Boolean = true) : CheckLink {
        override fun connect(timeoutMs: Long, cancelled: () -> Boolean) =
            if (connects) LinkOutcome(true) else LinkOutcome(false, CheckFix.WIFI)
        override fun client() = OscClient(cam)
        override fun files() = files
    }

    private class Clock { var t = 1_795_000_000_000L }

    private fun runner(link: CheckLink, gps: PhoneFix? = PhoneFix(10.776942, 106.700925, 12.0, 4.0),
                       exif: ExifGps? = ExifGps(10.776950, 106.700930, null), clock: Clock = Clock()) =
        ThetaCheckRunner(link, { gps }, { exif }, "12345678", now = { clock.t }, zone = ZoneId.of("Asia/Ho_Chi_Minh"),
            sleep = { clock.t += it }, pollMs = 300)

    private val goodFiles = OscFiles { u, max, _, _ -> if (u.endsWith("?type=thumb")) jpeg else jpeg.copyOf(minOf(max, jpeg.size)) }

    @Test fun allStepsPass() {
        val cam = FakeCamera()
        val clock = Clock()
        val s = runner(FakeLink(cam, goodFiles), clock = clock).run()
        for (r in s.results) assertEquals(r.step.name, CheckOutcome.OK, r.outcome)
        assertTrue(s.passed)
        assertEquals("RICOH THETA Z1", s.of(CheckStep.CAMERA).model)
        assertEquals(80, s.of(CheckStep.CAMERA).batteryPct)
        assertEquals(0.9, s.of(CheckStep.SHOT).shotSeconds!!, 1e-9)    // 3 polls of 300 ms
        assertNotNull(s.thumb)
        assertEquals(listOf("gpsInfo", "camera.takePicture", "camera.delete"), cam.commands)
    }

    @Test fun clockSetAgainWhenTheHandshakeCouldNot() {
        val cam = FakeCamera()
        val s = runner(FakeLink(cam, goodFiles, clockSet = false)).run()
        assertEquals(CheckOutcome.OK, s.of(CheckStep.CLOCK).outcome)
        assertEquals("dateTimeZone", cam.commands.first())
    }

    @Test fun thetaXGetsItsGpsSwitchOn() {
        val cam = FakeCamera(model = "RICOH THETA X")
        runner(FakeLink(cam, goodFiles)).run()
        assertEquals(listOf("_gpsTagRecording", "gpsInfo"), cam.commands.take(2))
    }

    @Test fun noWifiStopsEverything() {
        val cam = FakeCamera()
        val s = runner(FakeLink(cam, goodFiles, connects = false)).run()
        assertEquals(CheckOutcome.FAIL, s.of(CheckStep.WIFI).outcome)
        assertTrue(s.results.drop(1).all { it.outcome == CheckOutcome.SKIPPED })
        assertTrue(cam.commands.isEmpty())
    }

    @Test fun wrongCameraStopsBeforeAnything() {
        val cam = FakeCamera(serial = "99999999")
        val s = runner(FakeLink(cam, goodFiles)).run()
        assertEquals(CheckFix.WRONG_SERIAL, s.of(CheckStep.CAMERA).fix)
        assertTrue(s.results.drop(2).all { it.outcome == CheckOutcome.SKIPPED })
        assertTrue(cam.commands.isEmpty())
        val other = runner(FakeLink(FakeCamera(maker = "Insta360"), goodFiles)).run()
        assertEquals(CheckFix.NOT_RICOH, other.of(CheckStep.CAMERA).fix)
    }

    @Test fun noGpsFailsThatStepButTheShotGoesOn() {
        val cam = FakeCamera()
        val s = runner(FakeLink(cam, goodFiles), gps = null).run()
        assertEquals(CheckFix.NO_GPS, s.of(CheckStep.GPS).fix)
        assertEquals(CheckOutcome.OK, s.of(CheckStep.SHOT).outcome)
        assertEquals(CheckOutcome.OK, s.of(CheckStep.THUMB).outcome)
        assertEquals(CheckOutcome.SKIPPED, s.of(CheckStep.EXIF_GPS).outcome)   // nothing to compare with
        assertEquals(CheckOutcome.OK, s.of(CheckStep.DELETE).outcome)
        assertFalse(s.passed)
    }

    @Test fun photoWithoutGpsIsAWarning() {
        val s = runner(FakeLink(FakeCamera(), goodFiles), exif = ExifGps(null, null, null)).run()
        assertEquals(CheckOutcome.WARN, s.of(CheckStep.EXIF_GPS).outcome)
        assertTrue(s.passed)
    }

    @Test fun thumbFallsBackToTheStartOfTheFile() {
        val asked = mutableListOf<Pair<String, Boolean>>()
        val files = OscFiles { u, max, range, _ ->
            asked += u to range
            if (u.endsWith("?type=thumb")) throw IOException("HTTP 400") else jpeg.copyOf(minOf(max, jpeg.size))
        }
        val thumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
        val s = ThetaCheckRunner(FakeLink(FakeCamera(), files), { PhoneFix(10.776942, 106.700925, null, 4.0) },
            { ExifGps(10.776942, 106.700925, thumb) }, "12345678", sleep = {}).run()
        assertEquals(CheckOutcome.OK, s.of(CheckStep.THUMB).outcome)
        assertTrue(s.thumb!!.contentEquals(thumb))
        assertEquals(CheckOutcome.OK, s.of(CheckStep.EXIF_GPS).outcome)
        assertEquals(listOf("$url?type=thumb" to false, url to true), asked)   // the file's start read once, with a Range
    }

    @Test fun downloadFailing() {
        val files = OscFiles { _, _, _, _ -> throw IOException("reset") }
        val s = runner(FakeLink(FakeCamera(), files)).run()
        assertEquals(CheckFix.DOWNLOAD, s.of(CheckStep.THUMB).fix)
        assertEquals(CheckOutcome.FAIL, s.of(CheckStep.EXIF_GPS).outcome)
        assertEquals(CheckOutcome.OK, s.of(CheckStep.DELETE).outcome)
    }

    @Test fun deleteRefusedIsAWarning() {
        val s = runner(FakeLink(FakeCamera(refuse = setOf("camera.delete")), goodFiles)).run()
        assertEquals(CheckOutcome.WARN, s.of(CheckStep.DELETE).outcome)
        assertEquals(CheckFix.DELETE, s.of(CheckStep.DELETE).fix)
        assertTrue(s.passed)
    }

    @Test fun cancelledAfterTheShotStillDeletesIt() {
        val cam = FakeCamera()
        lateinit var r: ThetaCheckRunner
        r = runner(FakeLink(cam, goodFiles))
        val s = r.run { st -> if (st.of(CheckStep.SHOT).outcome == CheckOutcome.OK) r.cancel.set(true) }
        assertEquals(CheckOutcome.SKIPPED, s.of(CheckStep.THUMB).outcome)
        assertEquals(CheckOutcome.OK, s.of(CheckStep.DELETE).outcome)
        assertTrue("camera.delete" in cam.commands)
        assertFalse(s.running)
    }

    @Test fun cancelledBeforeStartDoesNothing() {
        val cam = FakeCamera()
        val r = runner(FakeLink(cam, goodFiles))
        r.cancel.set(true)
        val s = r.run()
        assertTrue(s.results.all { it.outcome == CheckOutcome.SKIPPED })
        assertTrue(cam.commands.isEmpty())
    }

    @Test fun readUpToStopsAtTheLimit() {
        val big = ByteArray(100_000) { it.toByte() }
        assertEquals(1000, HttpOscFiles.readUpTo(big.inputStream(), 1000).size)
        assertEquals(100_000, HttpOscFiles.readUpTo(big.inputStream(), 300_000).size)
    }
}

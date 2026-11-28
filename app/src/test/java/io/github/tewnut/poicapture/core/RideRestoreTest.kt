package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRestoreTest {
    private fun photo(id: String) = JSONObject().put("id", id).put("kind", "photo")

    @Test fun countsGoOnFromWhatIsOnDisk() {
        val items = listOf(
            photo("a").put("plates", JSONArray().put(JSONObject().put("kind", "number").put("text", "12"))),
            photo("b").put("plates", JSONArray().put(JSONObject().put("kind", "doubt"))),
            photo("c").put("signs", JSONArray().put(JSONObject().put("score", 0.8))),
            photo("d").put("signs", JSONArray().put(JSONObject().put("score", 0.2))),
            JSONObject().put("id", "v").put("kind", "video"),
        )
        val c = RideRestore.counts(items, trackLines = 300, saved = null)
        assertEquals(RideRestore.Counts(photos = 4, videos = 1, trackPoints = 300, plates = 1, signs = 1), c)
    }

    @Test fun theCountsSavedLastAreAFloor() {
        val saved = JSONObject().put("photos", 10).put("videos", 0).put("trackPoints", 500)
        val c = RideRestore.counts(listOf(photo("a")), trackLines = 200, saved = saved)
        assertEquals(10, c.photos)
        assertEquals(500, c.trackPoints)
    }

    @Test fun onlyAnEmptyRideThatNeverStartedIsDropped() {
        assertTrue(RideRestore.deletable(null, 0))
        assertFalse(RideRestore.deletable(null, 3))                       // photos on disk
        assertFalse(RideRestore.deletable("2026-11-28T08:00:00Z", 0))     // started: kept even with nothing yet
    }

    @Test fun rideEndKeepsPlatesAndFailedShots() {
        val a = RideEnd("s_a", cam360 = false, photos = 50, videos = 0, plates = 12)
        assertEquals(a, RideEnd.decode(a.encode()))
        val b = RideEnd("s_b", cam360 = true, photos = 0, videos = 0, thetaShots = 9, thetaSkipped = 2, thetaFailed = 1)
        assertEquals(b, RideEnd.decode(b.encode()))
        // a line saved before these fields
        assertEquals(RideEnd("s_c", cam360 = false, photos = 3, videos = 0), RideEnd.decode("s_c|0|3|0|-1|-1|-1"))
    }

    @Test fun aSyncCountsAfterThreeSeconds() {
        assertFalse(SyncGate.counts(1_000, 1_200))
        assertTrue(SyncGate.counts(1_000, 4_000))
        assertEquals(3, SyncGate.secondsLeft(1_000, 1_000))
        assertEquals(1, SyncGate.secondsLeft(1_000, 3_500))
        assertEquals(0, SyncGate.secondsLeft(1_000, 9_000))
        assertFalse(SyncGate.aligned(listOf(Camera360.Sync(0, 500, Camera360.When.START))))
        assertTrue(SyncGate.aligned(listOf(Camera360.Sync(0, 500, Camera360.When.START), Camera360.Sync(10_000, 14_000, Camera360.When.END))))
    }

    @Test fun anUploadProblemOfAPartNotSentIsShown() {
        val failed = RideUpload.Part(exists = true, status = UploadStatus.FAILED, error = "401", problem = UploadRecord.AUTH)
        assertEquals(UploadRecord.AUTH, RideUpload.of(true, listOf(failed)).problem)
        val sent = RideUpload.Part(exists = true, status = UploadStatus.DONE, problem = UploadRecord.NO_SPACE)
        assertEquals(null, RideUpload.of(true, listOf(sent)).problem)
    }

    @Test fun thetaSkipsAreKeptForARestart() {
        val c = ThetaRide.start(10.0)
        assertEquals(0 to 0, ThetaRide.skipped(c))
        ThetaRide.withSkipped(c, 2, 5)
        assertEquals(2 to 5, ThetaRide.skipped(c))
    }
}

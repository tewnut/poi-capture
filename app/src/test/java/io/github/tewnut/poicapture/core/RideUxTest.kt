package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideUxTest {
    @Test fun roadsStepGoesOnOnceADownloadStarted() {
        assertFalse(RoadsGate.canContinue(installed = false, downloading = false))   // nothing, or cancelled
        assertTrue(RoadsGate.canContinue(installed = false, downloading = true))
        assertTrue(RoadsGate.canContinue(installed = true, downloading = false))
    }

    @Test fun gpsHintAfterTwentySeconds() {
        assertFalse(GpsWait.showHint(null, 100_000))
        assertFalse(GpsWait.showHint(1_000, 20_999))
        assertTrue(GpsWait.showHint(1_000, 21_000))
    }

    @Test fun setupSummaryParts() {
        assertEquals(listOf(SetupSummary.Part.SWEEP, SetupSummary.Part.SIGNS, SetupSummary.Part.MOUNT),
            SetupSummary.parts(SessionKind.SWEEP, SweepTarget.SIGNS, use360 = false, thetaOn = true, holding = Holding.MOUNT))
        assertEquals(listOf(SetupSummary.Part.SWEEP, SetupSummary.Part.SHOPS, SetupSummary.Part.HAND),
            SetupSummary.parts(SessionKind.SWEEP, SweepTarget.SHOPS, use360 = false, thetaOn = false, holding = Holding.HAND))
        assertEquals(listOf(SetupSummary.Part.SWEEP, SetupSummary.Part.ADDRESSES, SetupSummary.Part.CAM360),
            SetupSummary.parts(SessionKind.SWEEP, SweepTarget.ADDRESSES, use360 = true, thetaOn = false, holding = Holding.HAND))
        assertEquals(listOf(SetupSummary.Part.SWEEP, SetupSummary.Part.SIGNS, SetupSummary.Part.THETA),
            SetupSummary.parts(SessionKind.SWEEP, SweepTarget.SIGNS, use360 = true, thetaOn = true, holding = Holding.MOUNT))
        assertEquals(listOf(SetupSummary.Part.PLACE),
            SetupSummary.parts(SessionKind.POI, SweepTarget.SIGNS, use360 = true, thetaOn = true, holding = Holding.MOUNT))
    }

    @Test fun rideEndRoundTrip() {
        val a = RideEnd("s_abc", cam360 = false, photos = 120, videos = 0, signs = 7)
        assertEquals(a, RideEnd.decode(a.encode()))
        val b = RideEnd("s_x", cam360 = true, photos = 0, videos = 0, thetaShots = 40, thetaSkipped = 3)
        assertEquals(b, RideEnd.decode(b.encode()))
        assertNull(RideEnd.decode(null))
        assertNull(RideEnd.decode("s_x|1|2"))
        assertNull(RideEnd.decode("s_x|1|a|0|0|0|0"))
    }

    @Test fun followsThePartsOnceTheRideIsCut() {
        assertEquals(listOf("s_1"), RideUpload.ids("s_1", emptyList(), emptyList()))
        assertEquals(listOf("s_1-1", "s_1-2"), RideUpload.ids("s_1", listOf("s_1-2", "s_1-1"), emptyList()))
        // part 1 already sent and removed from the phone: still followed (as sent)
        assertEquals(listOf("s_1-1", "s_1-2"), RideUpload.ids("s_1", listOf("s_1-2"), listOf("s_1-1", "s_1-2")))
    }

    @Test fun uploadState() {
        assertEquals(RideUpload.Kind.NO_TOKEN, RideUpload.of(false, listOf(RideUpload.Part(true))).kind)
        assertEquals(RideUpload.Kind.NOT_SENT, RideUpload.of(true, listOf(RideUpload.Part(true))).kind)
        // queued in the record, the work not seen yet
        assertEquals(RideUpload.Kind.WAITING, RideUpload.of(true, listOf(RideUpload.Part(true, UploadStatus.QUEUED))).kind)
        assertEquals(RideUpload.Kind.WIFI, RideUpload.of(true, listOf(RideUpload.Part(true, UploadStatus.QUEUED, queued = true, wifiWait = true))).kind)
        val sending = RideUpload.of(true, listOf(RideUpload.Part(true, UploadStatus.UPLOADING, queued = true, running = true, done = 25, total = 100)))
        assertEquals(RideUpload.Kind.SENDING, sending.kind)
        assertEquals(25, sending.pct)
        assertEquals(RideUpload.Kind.SENT, RideUpload.of(true, listOf(RideUpload.Part(true, UploadStatus.DONE))).kind)
        // sent, then removed from the phone
        assertEquals(RideUpload.Kind.SENT, RideUpload.of(true, listOf(RideUpload.Part(false))).kind)
        val failed = RideUpload.of(true, listOf(RideUpload.Part(true, UploadStatus.FAILED, error = "HTTP 500")))
        assertEquals(RideUpload.Kind.FAILED, failed.kind)
        assertEquals("HTTP 500", failed.error)
        assertEquals(RideUpload.Kind.FAILED, RideUpload.of(true, listOf(RideUpload.Part(true, UploadStatus.QUEUED, queued = true, error = "timeout"))).kind)
    }

    @Test fun partsTogether() {
        val parts = listOf(
            RideUpload.Part(true, UploadStatus.DONE, total = 100, done = 100),
            RideUpload.Part(true, UploadStatus.UPLOADING, queued = true, running = true, done = 50, total = 100),
            RideUpload.Part(true, UploadStatus.QUEUED, queued = true, wifiWait = true))
        val s = RideUpload.of(true, parts)
        assertEquals(RideUpload.Kind.SENDING, s.kind)
        assertEquals(75, s.pct)
        assertEquals(RideUpload.Kind.SENT, RideUpload.of(true, listOf(RideUpload.Part(false), RideUpload.Part(true, UploadStatus.DONE))).kind)
        assertEquals(RideUpload.Kind.WIFI, RideUpload.of(true, listOf(RideUpload.Part(false), RideUpload.Part(true, UploadStatus.QUEUED, queued = true, wifiWait = true))).kind)
    }
}

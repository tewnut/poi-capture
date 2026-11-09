package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun coverageIsThinnedSplitAtJumpsAndCached() {
        val track = (0..100).map { LatLon(START.lat, START.lon + it * 0.00001) } +            // ~1 m steps over 100 m
            (0..20).map { LatLon(START.lat + 0.01, START.lon + it * 0.0001) }                    // 1.1 km away: new line
        val lines = Coverage.simplify(track)
        assertEquals(2, lines.size)
        assertTrue(lines[0].size in 10..12)                                                     // ~10 m apart
        val c = Coverage.build(SessionKind.SWEEP, track, listOf(START))
        assertTrue(Coverage.intersects(c, START.lat - 0.001, START.lon - 0.001, START.lat + 0.001, START.lon + 0.001))
        assertFalse(Coverage.intersects(c, 10.0, 100.0, 10.1, 100.1))
        assertEquals(lines.size, Coverage.lines(c).size)
        assertEquals(1, Coverage.points(c).size)

        val store = SessionStore(tmp.newFolder("sessions"))
        val s = Session(id = "s_cov", createdAt = "2026-11-09T01:00:00Z", startedAt = "2026-11-09T01:00:00Z", contributor = "t",
            note = "", vehicle = Vehicle.UNSPECIFIED, mode = Mode.PHOTO_AUTO, sideOfTravel = Side.RIGHT, road = Road.auto(), settings = Settings())
        store.create(s)
        for ((i, p) in track.withIndex()) store.trackFile(s.id).appendText(Fix(i * 1000L, p.lat, p.lon, 5.0).toTrackJson(90.0).toString() + "\n")
        val cov = Coverage.forSession(store, s.id)!!
        assertEquals(2, Coverage.lines(cov).size)
        assertTrue(store.coverageFile(s.id).exists())
    }
}

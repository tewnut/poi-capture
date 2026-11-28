package io.github.tewnut.poicapture.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RideSplitTest {
    private fun ride(vararg runs: Pair<String?, Int>, startMs: Long = 0L, everyMs: Long = 2_000L): List<RideSplit.Photo> {
        var t = startMs
        return runs.flatMap { (street, n) -> List(n) { RideSplit.Photo(street, t.also { t += everyMs }) } }
    }

    @Test fun oneStreetIsNotCut() {
        assertEquals(listOf(0..39), RideSplit.parts(ride("Trường Sa" to 40)))
        assertEquals(emptyList(), RideSplit.parts(emptyList()))
    }

    @Test fun aNewStreetStartsAPart() {
        val photos = ride("Nguyễn Văn Thủ" to 30, "Trường Sa" to 50)
        val parts = RideSplit.parts(photos)
        assertEquals(listOf(0..29, 30..79), parts)
        assertEquals("Trường Sa", RideSplit.street(photos, parts[1]))
    }

    @Test fun aFewPhotosOnASideStreetOrUnnamedRoadDoNotCut() {
        val photos = ride("Trường Sa" to 20, "Hẻm 12" to 3, "null" to 2, "Unnamed road (service)" to 4, "Trường Sa" to 20)
        assertEquals(listOf(0..48), RideSplit.parts(photos))
    }

    @Test fun aLongStopStartsAPart() {
        val a = ride("Trường Sa" to 20)
        val b = ride("Trường Sa" to 20, startMs = a.last().tMs!! + 4 * 60_000L)
        assertEquals(listOf(0..19, 20..39), RideSplit.parts(a + b))
    }

    @Test fun aShortTailJoinsThePartBefore() {
        val a = ride("Trường Sa" to 20)
        val b = ride("Trường Sa" to 5, startMs = a.last().tMs!! + 4 * 60_000L)
        assertEquals(listOf(0..24), RideSplit.parts(a + b))
    }
}

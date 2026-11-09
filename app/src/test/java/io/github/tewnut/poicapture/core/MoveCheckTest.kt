package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveCheckTest {
    // a residential street running east from START; the camera rode along it, looking right (south)
    private val street = Way(1, "Test", "", "residential", "no", listOf(START, Geo.destination(START, 90.0, 200.0)))
    private val camera = Geo.destination(START, 90.0, 50.0)

    @Test fun aShopFrontOnTheCamerasSideIsFine() {
        val front = Geo.destination(camera, 180.0, 8.0)
        assertEquals(emptySet<MoveWarning>(), MoveCheck.warnings(camera, 180.0, front, listOf(street)))
    }

    @Test fun onTheRoadAcrossTheStreetAndTooFarAreFlagged() {
        assertTrue(MoveWarning.ON_ROAD in MoveCheck.warnings(camera, 180.0, Geo.destination(camera, 180.0, 1.5), listOf(street)))
        val across = Geo.destination(camera, 0.0, 8.0)
        assertTrue(MoveWarning.ACROSS in MoveCheck.warnings(camera, 180.0, across, listOf(street)))
        val far = Geo.destination(camera, 180.0, 25.0)
        assertEquals(setOf(MoveWarning.FAR), MoveCheck.warnings(camera, 180.0, far, listOf(street)))
    }

    @Test fun onFootTheSideComesFromWhereThePhotoWasTaken() {
        val onPavement = Geo.destination(camera, 180.0, 5.0)        // walking on the south side
        assertTrue(MoveWarning.ACROSS in MoveCheck.warnings(onPavement, null, Geo.destination(camera, 0.0, 6.0), listOf(street)))
        assertEquals(emptySet<MoveWarning>(), MoveCheck.warnings(onPavement, null, Geo.destination(camera, 180.0, 9.0), listOf(street)))
    }

    @Test fun footwaysAreNotRoads() {
        val path = Way(2, "", "", "footway", "no", listOf(START, Geo.destination(START, 90.0, 200.0)))
        assertEquals(emptySet<MoveWarning>(), MoveCheck.warnings(camera, 180.0, Geo.destination(camera, 180.0, 0.5), listOf(path)))
    }
}

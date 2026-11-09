package io.github.tewnut.roadcapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoTest {
    private val a = LatLon(21.0285, 105.8040)

    @Test fun distanceOfOneThousandthDegreeLongitudeInHanoi() {
        // 0.001° lon at 21.03° N ≈ 103.8 m
        assertEquals(103.8, Geo.distanceM(a, LatLon(21.0285, 105.8050)), 0.5)
    }

    @Test fun bearingsOfCardinalDirections() {
        assertEquals(90.0, Geo.bearing(a, LatLon(21.0285, 105.8050)), 0.1)
        assertEquals(0.0, Geo.bearing(a, LatLon(21.0295, 105.8040)), 0.1)
        assertEquals(270.0, Geo.bearing(a, LatLon(21.0285, 105.8030)), 0.1)
    }

    @Test fun angleDiffWrapsAround() {
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDiff(0.0, 180.0), 1e-9)
        assertEquals(90.0, Geo.angleDiff(-45.0, 45.0), 1e-9)
    }

    @Test fun nearestOnLineProjectsOntoSegment() {
        val line = listOf(LatLon(21.0285, 105.8040), LatLon(21.0285, 105.8060))
        val n = Geo.nearestOnLine(line, LatLon(21.0286, 105.8050))!!
        assertEquals(11.1, n.distanceM, 0.3)            // 0.0001° lat ≈ 11.1 m
        assertEquals(105.8050, n.point.lon, 1e-7)
        assertEquals(90.0, n.segmentBearing, 0.1)
    }

    @Test fun sideOfWayFlipsWhenDrivingAgainstTheDrawingDirection() {
        assertEquals(Side.RIGHT, sideOfWay(Side.RIGHT, Travel.FORWARD))
        assertEquals(Side.LEFT, sideOfWay(Side.RIGHT, Travel.BACKWARD))
        assertEquals(Side.RIGHT, sideOfWay(Side.LEFT, Travel.BACKWARD))
    }

    @Test fun cameraBearingIsPerpendicularPlusMountOffset() {
        assertEquals(180.0, cameraBearing(90.0, Side.RIGHT)!!, 1e-9)
        assertEquals(0.0, cameraBearing(90.0, Side.LEFT)!!, 1e-9)
        assertEquals(270.0, cameraBearing(10.0, Side.LEFT, -10.0)!!, 1e-9)   // 10 − 90 − 10 (turned 10° toward the rear)
        assertEquals(165.0, cameraBearing(90.0, Side.RIGHT, 15.0)!!, 1e-9)   // eastward, right side, 15° toward the front
        assertEquals(15.0, cameraBearing(90.0, Side.LEFT, 15.0)!!, 1e-9)     // eastward, left side, 15° toward the front
        assertNull(cameraBearing(null, Side.RIGHT))
    }
}

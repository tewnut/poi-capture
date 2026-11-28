package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Test

class Start360Test {
    @Test fun noCameraMustBeChosen() =
        assertEquals(Start360.CHOOSE_CAMERA, Start360.of(Camera360Model.NONE, "", connected = false, connecting = false))

    @Test fun camerasThatRecordByThemselvesStart() {
        for (m in listOf(Camera360Model.GOPRO, Camera360Model.INSTA360, Camera360Model.OTHER))
            assertEquals(Start360.READY, Start360.of(m, "", connected = false, connecting = false))
    }

    @Test fun thetaStartsOnlyConnected() {
        assertEquals(Start360.PAIR_THETA, Start360.of(Camera360Model.THETA, "", connected = false, connecting = false))
        assertEquals(Start360.CONNECT_THETA, Start360.of(Camera360Model.THETA, "12345678", connected = false, connecting = false))
        assertEquals(Start360.CONNECTING, Start360.of(Camera360Model.THETA, "12345678", connected = false, connecting = true))
        assertEquals(Start360.READY, Start360.of(Camera360Model.THETA, "12345678", connected = true, connecting = false))
    }
}

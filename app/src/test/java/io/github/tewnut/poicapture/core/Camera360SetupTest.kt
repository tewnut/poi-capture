package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class Camera360SetupTest {
    @Test fun modelMigratesFromTheOldThetaSwitch() {
        assertEquals(Camera360Model.THETA, Camera360Model.of(null, legacyThetaOn = true))
        assertEquals(Camera360Model.NONE, Camera360Model.of(null, legacyThetaOn = false))
        assertEquals(Camera360Model.GOPRO, Camera360Model.of("gopro", legacyThetaOn = true))   // a stored choice wins
        assertEquals(Camera360Model.NONE, Camera360Model.of("none", legacyThetaOn = true))
        assertEquals(Camera360Model.INSTA360, Camera360Model.of("insta360", legacyThetaOn = false))
        assertEquals(Camera360Model.NONE, Camera360Model.of("bogus", legacyThetaOn = false))
    }

    @Test fun serialFromLabels() {
        assertEquals("YR12345678", ThetaSerial.find("RICOH THETA X\nSN YR12345678\nMade in China"))
        assertEquals("YR12345678", ThetaSerial.find("S/N: yr12345678"))
        assertEquals("10012345", ThetaSerial.find("No. 10012345"))
        assertEquals("10012345", ThetaSerial.find("FCC ID: XXX\nNo.10012345"))
        assertEquals("10012345", ThetaSerial.find("SN\n10012345"))
        assertEquals("XS00123456", ThetaSerial.find("Serial No. XS00123456"))
        assertEquals("YN14000123", ThetaSerial.find("SSID THETAYN14000123.OSC"))
        assertEquals("YR12345678", ThetaSerial.find("Model R02010\nYR12345678"))   // a prefixed serial on its own
        assertEquals("YR12345678", ThetaSerial.find("SN YR 12345678"))
    }

    @Test fun noSerialInOtherNumbers() {
        assertNull(ThetaSerial.find("12345678"))                  // a lone 8-digit number: not without SN / No
        assertNull(ThetaSerial.find("SN 1234567"))                // 7 digits
        assertNull(ThetaSerial.find("No. 1234567890123"))         // a barcode's digits
        assertNull(ThetaSerial.find("4 901234 567894"))
        assertNull(ThetaSerial.find("RICOH THETA Z1 51GB"))
        assertNull(ThetaSerial.find(""))
    }

    @Test fun goproTimeCommand() {
        val local = LocalDateTime.of(2024, 12, 8, 17, 40, 33, 556_000_000)
        // the docs' own example: "oT241208174033.556 for 2024 December 8, at 5:40:33.556pm"
        assertEquals("oT241208174033.556oTD0oTZ-7", GoProLabs.timeCommand(local, -420, dst = false))
        assertEquals("oT241208174033.556oTD1oTZ-8", GoProLabs.timeCommand(local, -480, dst = true))
        assertEquals("oT241208174033.556oTD0oTZ330", GoProLabs.timeCommand(local, 330, dst = false))   // India: minutes
    }

    @Test fun goproSetupCommandFixedClock() {
        // 2026-11-28 03:15:02.123 UTC = 10:15:02.123 in Ho Chi Minh City (UTC+7, no daylight saving)
        val t = Instant.parse("2026-11-28T03:15:02.123Z")
        assertEquals("oT261128101502.123oTD0oTZ7g1mVm3r5p30", GoProLabs.setupCommand(t, ZoneId.of("Asia/Ho_Chi_Minh")))
        // summer in Los Angeles: wall clock PDT, standard offset -8 h, DST on (as GoPro's precision-time page)
        val s = Instant.parse("2026-07-01T19:00:00.007Z")
        assertEquals("oT260701120000.007oTD1oTZ-8g1mVm3r5p30", GoProLabs.setupCommand(s, ZoneId.of("America/Los_Angeles")))
        // whole-millisecond padding at a second's start
        assertEquals("oT261128101502.000oTD0oTZ7g1mVm3r5p30",
            GoProLabs.setupCommand(Instant.parse("2026-11-28T03:15:02Z"), ZoneId.of("Asia/Ho_Chi_Minh")))
    }
}

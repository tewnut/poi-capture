package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkSizerTest {
    private val KiB = 1024L
    private val MiB = 1024L * 1024

    @Test fun startsAtOneMebibyteWithAGenerousTimeout() {
        val s = ChunkSizer()
        assertEquals(1 * MiB, s.size)
        assertNull(s.rate)
        assertEquals(1 * MiB, s.next(10 * MiB))
        assertEquals(5L, s.next(5))
        assertEquals(0L, s.next(0))
        // nothing measured: 16 KiB/s assumed → 64 s → 4 × = 256 s
        assertEquals(256_000, s.timeoutMs(1 * MiB))
    }

    @Test fun slowNetworkShrinksAtOnceToAboutTwentySeconds() {
        val s = ChunkSizer()
        s.sent(1 * MiB, 45_000)                   // ~23 KB/s, as measured from Vietnam
        val rate = s.rate!!
        assertEquals(1 * MiB * 1000.0 / 45_000, rate, 0.01)
        assertEquals((rate * 20).toLong(), s.size)   // ~466 KiB
        assertTrue(s.expectedMs(s.size) in 19_000..21_000)
        assertEquals(80_000.0, s.timeoutMs(s.size).toDouble(), 200.0)
    }

    @Test fun neverBelowTheMinimumNorAboveTheMaximum() {
        val slow = ChunkSizer()
        repeat(5) { slow.sent(slow.size, 600_000) }
        assertEquals(256 * KiB, slow.size)
        val fast = ChunkSizer()
        repeat(20) { fast.sent(fast.size, 100) }
        assertEquals(8 * MiB, fast.size)
        // timeouts stay within 60 s .. 10 min
        assertEquals(60_000, fast.timeoutMs(fast.size))
        assertEquals(600_000, slow.timeoutMs(8 * MiB))
    }

    @Test fun growsGently() {
        val s = ChunkSizer()
        s.sent(1 * MiB, 1_000)                    // 1 MiB/s: 20 MiB would fit, but at most 1.5× per request
        assertEquals((1.5 * MiB).toLong(), s.size)
        s.sent(s.size, 1_500)
        assertEquals((2.25 * MiB).toLong(), s.size)
    }

    @Test fun aSpeedDropIsTakenAtOnceARiseIsSmoothed() {
        val s = ChunkSizer()
        s.sent(1 * MiB, 10_000)                   // 102.4 KiB/s
        s.sent(1 * MiB, 40_000)                   // 25.6 KiB/s: taken as is
        assertEquals(1 * MiB * 1000.0 / 40_000, s.rate!!, 0.01)
        val before = s.rate!!
        s.sent(512 * KiB, 1_000)                  // a burst: only 30 % of it counts
        assertEquals(before * 0.7 + 512 * KiB * 1000.0 * 0.3 / 1_000, s.rate!!, 0.01)
    }

    @Test fun failureHalvesTheChunkAndLengthensTheTimeout() {
        val s = ChunkSizer()
        s.sent(1 * MiB, 20_000)
        val size = s.size
        val timeout = s.timeoutMs(2 * MiB)
        s.failed()
        assertEquals(size / 2, s.size)
        assertTrue(s.timeoutMs(2 * MiB) > timeout)
        repeat(10) { s.failed() }
        assertEquals(256 * KiB, s.size)
    }

    @Test fun tinyRequestsAreNotMeasured() {
        val s = ChunkSizer()
        s.sent(10, 5_000)                         // the last few bytes of a file: latency, not speed
        assertNull(s.rate)
        assertEquals(1 * MiB, s.size)
    }
}

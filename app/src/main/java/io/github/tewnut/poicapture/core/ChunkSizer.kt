package io.github.tewnut.poicapture.core

import kotlin.math.ceil

/**
 * How many bytes the next tus PATCH carries, from the speed of the ones before (one sizer per connection). Measured
 * 2026-11-21: Cloudflare gives one connection from Vietnam ~23 KB/s, so a fixed 2 MiB PATCH took ~90 s against a 120 s
 * timeout; on a fast network the same chunk wastes round trips. Each request aims at [targetMs]: a slowdown shrinks the
 * next chunk at once (the lower speed is taken as is), a speed-up grows it by at most [GROW]× per request. A failed or
 * timed-out request halves it. The read timeout follows the expected duration.
 */
class ChunkSizer(
    val targetMs: Long = 20_000,
    val min: Long = 256L * 1024,
    val max: Long = 8L * 1024 * 1024,
    start: Long = 1L * 1024 * 1024,
    /** Bytes/s assumed before anything is measured: slow, so the first timeout is generous. */
    private val assumedRate: Double = 16.0 * 1024,
    val minTimeoutMs: Long = 60_000,
    val maxTimeoutMs: Long = 600_000,
) {
    /** The chunk for the next request (before capping at what is left). */
    var size: Long = start.coerceIn(min, max); private set
    /** Smoothed bytes/s; null until a request has been measured. */
    var rate: Double? = null; private set

    /** Bytes for the next PATCH when [remaining] bytes are left. */
    fun next(remaining: Long): Long = minOf(size, remaining).coerceAtLeast(0)

    /** How long [length] bytes should take at the measured (or assumed) speed. */
    fun expectedMs(length: Long): Long = ceil(length * 1000.0 / (rate ?: assumedRate)).toLong()

    /** Read timeout for a PATCH of [length] bytes: 4× the expected time, within [minTimeoutMs]..[maxTimeoutMs]. */
    fun timeoutMs(length: Long): Int = (4 * expectedMs(length)).coerceIn(minTimeoutMs, maxTimeoutMs).toInt()

    /** A PATCH moved the server's offset by [bytes] in [elapsedMs]. Tiny requests (latency-bound) are not measured. */
    fun sent(bytes: Long, elapsedMs: Long) {
        if (bytes < MIN_SAMPLE) return
        val r = bytes * 1000.0 / elapsedMs.coerceAtLeast(1)
        val old = rate
        val smoothed = if (old == null || r < old) r else old * 0.7 + r * 0.3   // down at once, up gently
        rate = smoothed
        val ideal = smoothed * targetMs / 1000.0
        val wanted = if (ideal < size) ideal else minOf(ideal, size * GROW)
        size = wanted.toLong().coerceIn(min, max)
    }

    /** A PATCH failed or timed out: half the chunk, and assume half the speed (a longer timeout next). */
    fun failed() {
        size = (size / 2).coerceIn(min, max)
        rate = rate?.let { it / 2 }
    }

    companion object {
        const val GROW = 1.5
        const val MIN_SAMPLE = 64L * 1024
    }
}

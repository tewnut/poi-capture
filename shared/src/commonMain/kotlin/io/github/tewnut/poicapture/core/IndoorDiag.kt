package io.github.tewnut.poicapture.core

import kotlin.math.abs
import kotlin.math.round

/**
 * The walk's diagnostics summary (docs/indoor.md §5, `session.indoor.diag`), counted as the indoor.jsonl lines are
 * written: lines per type, steps per source, anchors per `how`, and how far the position was off at each anchor (the
 * jump it made: the drift since the previous anchor), in metres and as % of the distance walked since it.
 */
class IndoorDiag {
    private val lines = LinkedHashMap<String, Int>()
    private val steps = LinkedHashMap<String, Int>()
    private val anchors = LinkedHashMap<String, Int>()
    private val corrections = mutableListOf<Double>()
    private val correctionPcts = mutableListOf<Double>()

    /** One indoor.jsonl line of [type] ("step", "lookup", "pdr", …). */
    fun line(type: String) { lines[type] = (lines[type] ?: 0) + 1 }

    fun step(source: String) { line("step"); steps[source] = (steps[source] ?: 0) + 1 }

    /**
     * An anchor of [how]; [jumpM] = how far the live position was from it, [sinceM] = metres walked since the previous
     * anchor. The jump counts as a correction only when the walk was [anchoredBefore] (the first one fixes a guess).
     */
    fun anchor(how: String, jumpM: Double, sinceM: Double, anchoredBefore: Boolean) {
        line("anchor")
        anchors[how] = (anchors[how] ?: 0) + 1
        if (!anchoredBefore || jumpM.isNaN()) return
        corrections += abs(jumpM)
        if (sinceM >= MIN_SINCE_M) correctionPcts += abs(jumpM) / sinceM * 100
    }

    /** The summary: plain maps and numbers (the app writes it as JSON). */
    fun summary(): Map<String, Any?> = linkedMapOf(
        "lines" to LinkedHashMap(lines),
        "steps" to LinkedHashMap(steps),
        "anchors" to LinkedHashMap(anchors),
        "corrections" to corrections.size,
        "meanCorrectionM" to corrections.takeIf { it.isNotEmpty() }?.let { r1(it.average()) },
        "meanCorrectionPct" to correctionPcts.takeIf { it.isNotEmpty() }?.let { r1(it.average()) },
    )

    private fun r1(d: Double) = round(d * 10) / 10

    companion object {
        /** shorter stretches say nothing about drift in % (a tap is a few metres off) */
        const val MIN_SINCE_M = 5.0
    }
}

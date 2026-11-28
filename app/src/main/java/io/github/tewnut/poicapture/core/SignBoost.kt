package io.github.tewnut.poicapture.core

/**
 * Traffic-signs ride: denser photos while a sign is in view (docs/sweep-targets.md, "Sign boost").
 *
 * The camera's preview is checked for signs a few times a second ([SignBoxes] on a small frame). While one was seen
 * in the last [holdNs], photos come [factor] times as often (8 m -> ~3 m), so each sign is on 3+ photos and can be
 * triangulated. When a sign reaches the edge of the frame (the closest sharp view, the one with its extra plate) one
 * more photo is taken at once, at most every [edgeGapNs]. Pure logic: the service feeds it boxes and asks.
 */
class SignBoost(
    val factor: Double = 2.6,
    private val holdNs: Long = 2_000_000_000L,
    private val edgeGapNs: Long = 1_500_000_000L,
    private val minScore: Float = SignBoxes.SEEN,
) {
    private var seenNs: Long? = null
    private var edgeNs: Long? = null
    var seenFrames = 0; private set
    var edgeShots = 0; private set

    /** True while photos should come [factor] times as often. */
    fun active(nowNs: Long): Boolean = seenNs?.let { nowNs - it in 0..holdNs } ?: false

    /** How much faster the distance counts toward the next photo (1 = normal spacing). */
    fun speedUp(nowNs: Long): Double = if (active(nowNs)) factor else 1.0

    /** A preview frame's signs (boxes as fractions of the upright frame). Returns true when an edge photo is due now. */
    fun onFrame(nowNs: Long, boxes: List<SignBox>): Boolean {
        val strong = boxes.filter { it.score >= minScore }
        if (strong.isEmpty()) return false
        seenNs = nowNs
        seenFrames++
        // near enough to be readable, and about to leave the frame (signs slide outward and up as you approach)
        val leaving = strong.any { b -> (b.x1 - b.x0) >= MIN_EDGE_WIDTH && (b.x0 <= EDGE || b.x1 >= 1 - EDGE || b.y0 <= TOP_EDGE) }
        if (!leaving || edgeNs?.let { nowNs - it < edgeGapNs } == true) return false
        edgeNs = nowNs
        edgeShots++
        return true
    }

    companion object {
        const val EDGE = 0.10f          // within 10 % of the left / right side
        const val TOP_EDGE = 0.06f      // or touching the top (gantries, tall poles)
        const val MIN_EDGE_WIDTH = 0.035f   // a box narrower than this is still far: the boosted spacing covers it
    }
}

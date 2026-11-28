package io.github.tewnut.poicapture.core

/**
 * The traffic-sign detector's output, turned into boxes (ml/signs/README.md; the same steps as `postprocess` in
 * ml/signs/signs_lib.py). The model sees the photo resized to fit [size]×[size], placed top-left, padded with 114.
 * Each of its rows is cx, cy, w, h (pixels of that square), objectness, then one probability per class;
 * score = objectness × the best class's probability. Pure Kotlin so it is tested on the JVM.
 */
data class SignClass(val code: String, val group: String, val label: String)

/** A sign on a photo: box as fractions of the upright photo (0..1, left/top/right/bottom), best class, score. */
data class SignBox(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val score: Float, val cls: Int) {
    val area get() = (x1 - x0).coerceAtLeast(0f) * (y1 - y0).coerceAtLeast(0f)
}

object SignBoxes {
    /** Stored as a hint for the agent (it checks every box: about half are letters or logos on shopfronts). */
    const val KEEP = 0.3f
    /** Counts as "a sign in view" for the rider's live count (one false box in ~12 on the Trường Sa test ride). */
    const val SEEN = 0.5f

    /** classes.tsv: id, QCVN code (empty when the class has no single code), group, Vietnamese label. */
    fun classes(tsv: String): List<SignClass> = tsv.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }
        .sortedBy { it[0].trim().toInt() }
        .map { SignClass(it.getOrElse(1) { "" }.trim(), it.getOrElse(2) { "" }.trim(), it.getOrElse(3) { "" }.trim()) }
        .toList()

    /**
     * Rows of [stride] floats (5 + classes) → boxes with score ≥ [minScore], class-agnostic NMS (one box per sign),
     * best first. [scale] = the resize factor photo → model input, [w]/[h] = the upright photo size it was given at.
     */
    fun decode(out: FloatArray, stride: Int, scale: Float, w: Int, h: Int, minScore: Float = KEEP, iou: Float = 0.45f): List<SignBox> {
        val found = ArrayList<SignBox>()
        val rows = out.size / stride
        for (r in 0 until rows) {
            val o = r * stride
            val obj = out[o + 4]
            if (obj < minScore) continue          // score ≤ objectness: skip early
            var best = 0
            for (c in 1 until stride - 5) if (out[o + 5 + c] > out[o + 5 + best]) best = c
            val score = obj * out[o + 5 + best]
            if (score < minScore) continue
            val cx = out[o] / scale; val cy = out[o + 1] / scale
            val bw = out[o + 2] / scale; val bh = out[o + 3] / scale
            found += SignBox(((cx - bw / 2) / w).coerceIn(0f, 1f), ((cy - bh / 2) / h).coerceIn(0f, 1f),
                ((cx + bw / 2) / w).coerceIn(0f, 1f), ((cy + bh / 2) / h).coerceIn(0f, 1f), score, best)
        }
        found.sortByDescending { it.score }
        val kept = ArrayList<SignBox>()
        for (b in found) if (kept.none { overlap(it, b) > iou }) kept += b
        return kept
    }

    fun overlap(a: SignBox, b: SignBox): Float {
        val ix = (minOf(a.x1, b.x1) - maxOf(a.x0, b.x0)).coerceAtLeast(0f)
        val iy = (minOf(a.y1, b.y1) - maxOf(a.y0, b.y0)).coerceAtLeast(0f)
        val inter = ix * iy
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

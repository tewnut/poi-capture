package io.github.tewnut.poicapture.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Floor plans (docs/indoor.md §7): a photographed mall board, straightened, placed on the map by a similarity
// transform. Pure math here; the bitmaps, the screen and the files are the app's.

private fun rad(d: Double) = d * PI / 180

/** [v] turned [deg] clockwise on the map (x east, y north). */
fun rotateCw(v: Xy, deg: Double): Xy {
    val c = cos(rad(deg)); val s = sin(rad(deg))
    return Xy(v.x * c + v.y * s, -v.x * s + v.y * c)
}

/**
 * Plan pixel (x right, y down) → map, as in docs/indoor.md §7 `toMap`: the pixel vector (y flipped, so up is north)
 * turned [rotationDeg] clockwise, times [scaleMPerPx], = metres east / north of [origin] (the map position of plan
 * pixel 0,0). [rotationDeg] is therefore also the bearing the plan's top points to.
 */
data class PlanToMap(val origin: LatLon, val scaleMPerPx: Double, val rotationDeg: Double) {
    private val frame get() = LocalFrame(origin)

    fun toMap(x: Double, y: Double): LatLon = frame.ll(rotateCw(Xy(x, -y), rotationDeg) * scaleMPerPx)

    /** Map → plan pixel (the inverse). */
    fun toPlan(p: LatLon): Xy {
        val v = rotateCw(frame.xy(p) * (1 / scaleMPerPx), -rotationDeg)
        return Xy(v.x, -v.y)
    }

    /** Dragged on the map from [from] to [to]. */
    fun moved(from: LatLon, to: LatLon): PlanToMap = copy(origin = LocalFrame(origin).ll(LocalFrame(from).xy(to)))

    /** Scaled by [k] and turned [dRotDeg] clockwise about [pivot] (two fingers: pinch and twist). */
    fun adjusted(pivot: LatLon, k: Double, dRotDeg: Double): PlanToMap {
        val f = LocalFrame(pivot)
        return PlanToMap(f.ll(rotateCw(f.xy(origin), dRotDeg) * k), scaleMPerPx * k, Geo.norm360(rotationDeg + dRotDeg))
    }

    companion object {
        /** The transform with plan pixel [px] at [at], turned [rotationDeg], [scaleMPerPx]. */
        fun at(px: Xy, at: LatLon, scaleMPerPx: Double, rotationDeg: Double): PlanToMap =
            PlanToMap(LocalFrame(at).ll(rotateCw(Xy(px.x, -px.y), rotationDeg) * -scaleMPerPx), scaleMPerPx, Geo.norm360(rotationDeg))

        /** "Ghim 2 điểm": plan pixel [p1] is at [m1] on the map, [p2] at [m2]. Null when the points are too close. */
        fun fromTwoPoints(p1: Xy, m1: LatLon, p2: Xy, m2: LatLon): PlanToMap? {
            val f = LocalFrame(m1)
            val w = f.xy(m2)
            val v = Xy(p2.x - p1.x, -(p2.y - p1.y))
            if (v.length < 5 || w.length < 0.5) return null
            return at(p1, m1, w.length / v.length, w.bearing - v.bearing)
        }
    }
}

/** The smallest rectangle around a shape: centre, the bearing of its first axis, its lengths along both axes (m). */
data class Obb(val center: LatLon, val axisDeg: Double, val lengthM: Double, val widthM: Double)

object PlanFit {
    /** Minimum-area oriented bounding box of a ring (rotating the hull's edges). Null for fewer than 3 points. */
    fun obb(ring: List<LatLon>): Obb? {
        if (ring.size < 3) return null
        val f = LocalFrame(LatLon(ring.map { it.lat }.average(), ring.map { it.lon }.average()))
        val hull = hull(ring.map { f.xy(it) })
        if (hull.size < 3) return null
        var best: Obb? = null
        var bestArea = Double.MAX_VALUE
        for (i in hull.indices) {
            val e = hull[(i + 1) % hull.size] - hull[i]
            if (e.length < 1e-9) continue
            val d = e * (1 / e.length)
            val p = Xy(-d.y, d.x)
            var a0 = Double.MAX_VALUE; var a1 = -Double.MAX_VALUE; var b0 = Double.MAX_VALUE; var b1 = -Double.MAX_VALUE
            for (q in hull) {
                val a = q.x * d.x + q.y * d.y; val b = q.x * p.x + q.y * p.y
                a0 = min(a0, a); a1 = max(a1, a); b0 = min(b0, b); b1 = max(b1, b)
            }
            val area = (a1 - a0) * (b1 - b0)
            if (area < bestArea - 1e-9) {
                bestArea = area
                val c = d * ((a0 + a1) / 2) + p * ((b0 + b1) / 2)
                best = Obb(f.ll(c), d.bearing, a1 - a0, b1 - b0)
            }
        }
        return best
    }

    /** Convex hull (monotone chain), counter-clockwise. */
    fun hull(pts: List<Xy>): List<Xy> {
        val s = pts.distinct().sortedWith(compareBy<Xy>({ it.x }, { it.y }))
        if (s.size < 3) return s
        fun cross(o: Xy, a: Xy, b: Xy) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = mutableListOf<Xy>()
        for (p in s) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1); lower += p }
        val upper = mutableListOf<Xy>()
        for (p in s.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1); upper += p }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** A first placement and how well the plan's rectangle covers the footprint's box (intersection over union). */
    data class Fit(val toMap: PlanToMap, val coverage: Double)

    /**
     * First guess for a plan of [w] × [h] px in a building: its rectangle on the footprint's oriented bounding box,
     * centred, area for area, along the box's axes. Of the 4 rotations the ones whose shape covers the box best are
     * kept (within [TIE]); a plan turned 180° covers it exactly as well, so between those the one whose top points
     * closest to [upHintDeg] wins (the camera's direction when the board was photographed: boards are drawn as seen
     * by whoever stands in front of them), north when unknown.
     */
    fun fitToFootprint(w: Int, h: Int, ring: List<LatLon>, upHintDeg: Double? = null): Fit? {
        if (w <= 0 || h <= 0) return null
        val box = obb(ring) ?: return null
        if (box.lengthM < 1 || box.widthM < 1) return null
        val f = LocalFrame(box.center)
        val cands = (0..3).map { k ->
            val r = Geo.norm360(box.axisDeg - 90 + 90.0 * k)      // the plan's x axis along the box's first axis (k even)
            val (ex, ey) = if (k % 2 == 0) box.lengthM to box.widthM else box.widthM to box.lengthM
            val s = sqrt(ex * ey / (w.toDouble() * h))
            val pw = w * s; val ph = h * s
            val inter = min(pw, ex) * min(ph, ey)
            val iou = inter / (pw * ph + ex * ey - inter)
            val origin = f.ll(rotateCw(Xy(w / 2.0, -h / 2.0), r) * -s)
            Fit(PlanToMap(origin, s, r), iou)
        }
        val top = cands.maxOf { it.coverage }
        val up = upHintDeg ?: 0.0
        return cands.filter { it.coverage >= top - TIE }.minByOrNull { Geo.angleDiff(it.toMap.rotationDeg, up) }
    }
    const val TIE = 0.03

    /**
     * No footprint: the plan with its "you are here" dot (or its centre) at [at], its top toward [upDeg] (or north), at a
     * guessed scale: its long edge ≈ [longEdgeM]. The walker then pins 2 points or drags it.
     */
    fun guess(w: Int, h: Int, at: LatLon, youAreHere: Xy?, upDeg: Double?, longEdgeM: Double = 150.0): PlanToMap =
        PlanToMap.at(youAreHere ?: Xy(w / 2.0, h / 2.0), at, longEdgeM / max(w, h), upDeg ?: 0.0)

    /**
     * The straightened plan's size from the board's corners on the photo (px, clockwise from top-left): the mean of
     * opposite sides, long edge at most [maxLong].
     */
    fun straightSize(c: List<Xy>, maxLong: Int = 2048): Pair<Int, Int> {
        require(c.size == 4)
        val w = ((c[1] - c[0]).length + (c[2] - c[3]).length) / 2
        val h = ((c[3] - c[0]).length + (c[2] - c[1]).length) / 2
        val k = min(1.0, maxLong / max(max(w, h), 1.0))
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }
}

/**
 * Where the board is on a photo, for the straightening's first guess (the walker drags the corners anyway): the
 * largest 4-cornered outline of strong edges. On a small grayscale copy: Sobel edges above mean + 1 σ, joined into
 * 8-connected pieces; each large piece's extreme points along the diagonals (top-left = min x+y, …) make a quad, kept
 * when edges really run along its 4 sides. Null = nothing convincing (the app then starts from the photo's corners).
 */
object BoardFinder {
    /** Corners as fractions of the image (x, y), clockwise from top-left, or null. */
    fun find(gray: IntArray, w: Int, h: Int): List<Xy>? {
        if (w < 16 || h < 16 || gray.size < w * h) return null
        val mag = FloatArray(w * h)
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            fun g(dx: Int, dy: Int) = gray[(y + dy) * w + x + dx]
            val gx = (g(1, -1) + 2 * g(1, 0) + g(1, 1)) - (g(-1, -1) + 2 * g(-1, 0) + g(-1, 1))
            val gy = (g(-1, 1) + 2 * g(0, 1) + g(1, 1)) - (g(-1, -1) + 2 * g(0, -1) + g(1, -1))
            val m = sqrt((gx * gx + gy * gy).toDouble())
            mag[y * w + x] = m.toFloat(); sum += m; sum2 += m * m; n++
        }
        val mean = sum / n
        val sd = sqrt(max(0.0, sum2 / n - mean * mean))
        val t = max(40.0, mean + sd)
        val edge = BooleanArray(w * h) { mag[it] >= t }
        val seen = BooleanArray(w * h)
        val imgArea = w.toDouble() * h
        var best: List<Xy>? = null
        var bestScore = 0.0
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!edge[start] || seen[start]) continue
            // one piece: its pixels' extreme points along the diagonals
            var sp = 0; stack[sp++] = start; seen[start] = true
            var count = 0
            var tl = start; var br = start; var tr = start; var bl = start
            fun sumOf(i: Int) = i % w + i / w
            fun difOf(i: Int) = i % w - i / w
            while (sp > 0) {
                val i = stack[--sp]; count++
                if (sumOf(i) < sumOf(tl)) tl = i
                if (sumOf(i) > sumOf(br)) br = i
                if (difOf(i) > difOf(tr)) tr = i
                if (difOf(i) < difOf(bl)) bl = i
                val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (edge[j] && !seen[j]) { seen[j] = true; stack[sp++] = j }
                }
            }
            if (count < 40) continue
            val q = listOf(tl, tr, br, bl).map { Xy((it % w).toDouble(), (it / w).toDouble()) }
            val area = quadArea(q)
            if (area < MIN_AREA * imgArea || !convex(q)) continue
            val support = (0..3).map { k -> sideSupport(edge, w, h, q[k], q[(k + 1) % 4]) }
            if (support.min() < 0.35 || support.average() < 0.55) continue
            val score = area / imgArea * support.average()
            if (score > bestScore) { bestScore = score; best = q }
        }
        return best?.map { Xy(it.x / (w - 1), it.y / (h - 1)) }
    }

    const val MIN_AREA = 0.08

    fun quadArea(q: List<Xy>): Double {
        var s = 0.0
        for (i in q.indices) { val a = q[i]; val b = q[(i + 1) % q.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2
    }

    fun convex(q: List<Xy>): Boolean {
        var sign = 0
        for (i in q.indices) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val cr = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cr) < 1e-9) return false
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Share of points along a → b with an edge pixel within 2 px. */
    private fun sideSupport(edge: BooleanArray, w: Int, h: Int, a: Xy, b: Xy): Double {
        val steps = 40
        var hit = 0
        for (k in 0..steps) {
            val x = (a.x + (b.x - a.x) * k / steps).roundToInt(); val y = (a.y + (b.y - a.y) * k / steps).roundToInt()
            var found = false
            loop@ for (dy in -2..2) for (dx in -2..2) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && edge[yy * w + xx]) { found = true; break@loop }
            }
            if (found) hit++
        }
        return hit / (steps + 1.0)
    }
}

/** A unit number read on a straightened plan: its label and the centre / height of its box in plan px. */
data class PlanUnit(val unit: String, val x: Double, val y: Double, val h: Double)

/**
 * Unit numbers on a floor plan (docs/indoor.md §7 `units`), read on the phone as hints: "L2-15", "B1-03", "215",
 * "2.15", "K-05", "SH 12". Prices, phone numbers, years, times and sizes are not units. A bare number is kept when it
 * is short (≤ 2 digits) or starts with the plan's level ("215" on level 2), without a level any 2–4 digit number.
 */
object PlanUnits {
    /** A word read on the plan with its box (left, top, right, bottom) in plan px. */
    class Word(val text: String, val box: IntArray?)

    private val LEVELED = Regex("""^([LBTG])(\d{1,2})[-.]?([A-Z]{0,2}\d{1,3}[A-Z]?)$""")      // L2-15, B1-03, L3.K05
    private val DOTTED = Regex("""^(\d{1,2})[.\-](\d{1,3}[A-Z]?)$""")                     // 2.15, 2-15
    private val BARE = Regex("""^(\d{1,4})([A-Z]?)$""")                                     // 215, 15, 215A
    private val LETTERS = Regex("""^([A-Z]{1,3})[-.]?(\d{1,3}[A-Z]?)$""")                   // K-05, K05, SH12
    private val PRICE = Regex("""(\d[.,]\d{3})|(\d\s?(K|Đ|D|VND|\$|%)$)|(^\$)""")
    private val NOT_UNIT_PREFIX = setOf("TEL", "HOTLINE", "WC", "EXIT", "GATE", "COUNTER", "LEVEL", "TANG", "FLOOR", "NO", "SO")

    /**
     * The unit number in one word ([word]) or a word and the next ([next]: "SH" "12"); null when it is not one.
     * [level] = the plan's level name ("2", "B1") as a hint.
     */
    fun unitOf(word: String, next: String? = null, level: String? = null): String? {
        val w = clean(word)
        if (w.isEmpty() || w.none { it.isDigit() } && next == null) return null
        if (next != null && w.all { it.isLetter() } && w.length <= 3 && w !in NOT_UNIT_PREFIX) {
            val n = clean(next)
            if (Regex("""^\d{1,3}[A-Z]?$""").matches(n)) return "$w $n"
        }
        if (w.none { it.isDigit() } || w.contains(':') || PRICE.containsMatchIn(w)) return null
        LEVELED.matchEntire(w)?.let { return w }
        DOTTED.matchEntire(w)?.let { m ->
            val lv = m.groupValues[1]
            if (level != null && lv != level && lv != level.removePrefix("B")) return null
            return w
        }
        BARE.matchEntire(w)?.let { m ->
            val d = m.groupValues[1]
            if (d.length == 4 && d.toInt() in 1950..2035) return null   // a year (more often than unit 2019 on level 2)
            if (d.length <= 2) return w
            if (level == null || level.startsWith("B")) return w.takeIf { d.length in 2..4 }
            return w.takeIf { d.startsWith(level) }
        }
        LETTERS.matchEntire(w)?.let { m ->
            val p = m.groupValues[1]
            if (p in setOf("L", "B", "T", "G") && m.groupValues[2].length == 1) return null   // "L2", "B1": a level
            if (p !in NOT_UNIT_PREFIX) return w
        }
        return null
    }

    private fun clean(s: String) = s.trim().trim(',', ';', ':', '(', ')', '[', ']', '"', '\'').uppercase()
        .replace('–', '-').replace('—', '-')

    /** Unit numbers on a plan, from its lines of words (each word's box in plan px); one per label. */
    fun read(lines: List<List<Word>>, level: String?): List<PlanUnit> {
        val out = mutableListOf<PlanUnit>()
        for (line in lines) {
            var i = 0
            while (i < line.size) {
                val w = line[i]; val n = line.getOrNull(i + 1)
                val pair = n?.let { unitOf(w.text, it.text, level) }?.takeIf { it.contains(' ') }
                val u = pair ?: unitOf(w.text, null, level)
                if (u != null) {
                    val boxes = listOfNotNull(w.box, if (pair != null) n.box else null)
                    if (boxes.isNotEmpty()) {
                        val l = boxes.minOf { it[0] }; val t = boxes.minOf { it[1] }; val r = boxes.maxOf { it[2] }; val b = boxes.maxOf { it[3] }
                        out += PlanUnit(u, (l + r) / 2.0, (t + b) / 2.0, (b - t).toDouble())
                    }
                }
                i += if (pair != null) 2 else 1
            }
        }
        return out
    }

    /** The plan unit nearest to [at] on the map (within [maxM]), with its distance in metres. */
    fun nearest(units: List<PlanUnit>, toMap: PlanToMap, at: LatLon, maxM: Double = 25.0): Pair<PlanUnit, Double>? =
        units.map { it to Geo.distanceM(at, toMap.toMap(it.x, it.y)) }.filter { it.second <= maxM }.minByOrNull { it.second }
}

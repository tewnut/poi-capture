package io.github.tewnut.poicapture.core

/**
 * House-number hints on a house-number ride (docs/sweep-targets.md): which of the text lines the phone read on a
 * photo look like a house number ("12", "12A", "12/3", "38/1", "170-172", "K12"), and which only look like one.
 *
 * On the rides of 24 Nov 2026 about half to two thirds of the old hints were not house numbers: prices ("45K",
 * "299K"), pieces of phone numbers ("0963", "5483"), dates ("25/11/2026"), years ("EST 1976"), vehicle plates
 * ("51B-403.79", "50H"), traffic-sign values ("2.5T"), "Cơ sở 2", and the street's own number on every sign of
 * "Đường số 18". The obvious ones are dropped (kept apart with the reason, so the agent can still check), the doubtful
 * ones are kept with a reason, so a real number is never silently lost. A hint is only a pointer to where to look:
 * the agent reads the number itself on the full photo.
 */
object HouseNumbers {
    enum class Kind(val json: String) {
        /** Looks like a house number. */
        NUMBER("number"),
        /** Could be one, see [Hint.why]: kept for the agent, not counted for the rider. */
        DOUBT("doubt"),
        /** Not a house number, see [Hint.why]. */
        DROP("drop"),
    }

    /** One line read on the photo; [block] groups the lines the reader found in one block of text (-1: unknown). */
    class Line(val text: String, val box: IntArray? = null, val block: Int = -1)

    /**
     * A number found on a line. [hFrac] = height of the line's box / photo height (a door plate's digits are small, a
     * shop sign's big), [yFrac] = the box's centre from the top (0) to the bottom (1). [src]: "plate" when the number
     * stands alone in its block (perhaps with its street / ward line), "sign" when it is written among other words.
     */
    data class Hint(
        val text: String,
        val kind: Kind,
        val why: String?,
        val line: String,
        val box: IntArray?,
        val heightPx: Int,
        val hFrac: Double?,
        val yFrac: Double?,
        val src: String,
    )

    /** Lowercase without Vietnamese diacritics, đ → d (common code: no java.text.Normalizer). */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            if (c in '̀'..'ͯ') continue          // combining marks (decomposed input)
            sb.append(FOLD[c] ?: c)
        }
        return sb.toString()
    }
    private val FOLD: Map<Char, Char> = listOf(
        'a' to "àáạảãâầấậẩẫăằắặẳẵ", 'e' to "èéẹẻẽêềếệểễ", 'i' to "ìíịỉĩ", 'o' to "òóọỏõôồốộổỗơờớợởỡ",
        'u' to "ùúụủũưừứựửữ", 'y' to "ỳýỵỷỹ", 'd' to "đ",
    ).flatMap { (base, accented) -> accented.map { it to base } }.toMap()

    // a house number: optional K (kiệt, "K12/3"), digits (5+ caught to be dropped), a letter, then up to 3 parts
    private const val TOKEN = "k?\\d{1,6}[a-z]?(?:\\s?[/-]\\s?\\d{1,4}[a-z]?){0,3}"
    private const val STREET = "(?:pho|duong|ngo|ngach|hem|kiet|p\\.|d\\.|q\\.|to|khu|thon|xom|ql|tl)"
    private val ALONE = Regex("^(?:so nha|so|sn|no)?[:.\\s]*($TOKEN)[.:,]?$")
    private val KEYWORD = Regex("(?:^|\\b)(?:so nha|so|sn|dia chi|dc|d/c|add|address)[:.\\s]+($TOKEN)\\b")
    private val BEFORE_STREET = Regex("(?:^|\\s)($TOKEN)\\s*,?\\s+$STREET\\b")
    private val LINE_START = Regex("^\\s*($TOKEN)\\s+[a-z]{2,}\\s+[a-z]{2,}")
    private val OLD = Regex("\\bso cu[:.\\s]*($TOKEN)\\b")

    // numbers that name something else: street ("Đường số 18", "Đường 16", "QL 1A"), branch ("Cơ sở 2"), ward,
    // district, quarter, kilometre post. Blanked out before looking for a house number (the street word is kept, so
    // "12 Đường số 18" still gives 12); street and branch numbers are recorded as dropped.
    private class Named(val re: Regex, val why: String, val keep: String)
    private val NAMED = listOf(
        Named(Regex("\\b(?:duong|d\\.)\\s*(?:so\\s*)?(\\d{1,3}[a-z]?)\\b"), "street name", " duong ~ "),
        Named(Regex("\\b(?:ql|quoc lo|tl|tinh lo)\\.?\\s*(\\d{1,3}[a-z]?)\\b"), "road number", " duong ~ "),
        Named(Regex("\\bco so\\s*(\\d{1,3})\\b"), "branch number", " ~ "),
        Named(Regex("\\b(?:phuong|p\\.)\\s*(\\d{1,2})\\b"), "ward", " ~ "),
        Named(Regex("\\b(?:quan|q\\.)\\s*(\\d{1,2})\\b"), "district", " ~ "),
        Named(Regex("\\b(?:khu pho|kp|ap|khom)\\.?\\s*(\\d{1,3}[a-z]?)\\b"), "quarter", " ~ "),
        Named(Regex("\\bkm\\s*(\\d{1,4})\\b"), "kilometre post", " ~ "),
    )
    private val RECORDED = setOf("street name", "branch number")

    // spans of a line that are something else: a number found inside one is dropped
    private val SPANS = listOf(
        Regex("(?:\\+?84|\\b0)\\d{2,3}[\\s.\\-]?\\d{3}[\\s.\\-]?\\d{3,4}\\b|\\b1[89]00[\\s.\\-]?\\d{4}\\b") to "phone number",
        Regex("\\d{5,}") to "5+ digits (phone, account or code)",
        Regex("\\b\\d{1,2}[/.\\-]\\d{1,2}[/.\\-](?:19|20)\\d{2}\\b") to "date",   // "12/3/45" is a house number
        Regex("\\b\\d{1,2}\\s*[h:]\\s*\\d{0,2}\\s*[-–]\\s*\\d{1,2}\\s*[h:]?\\s*\\d{0,2}|\\b\\d{1,2}:\\d{2}\\b") to "opening hours",
        Regex("\\b\\d{2}\\s*-?\\s*[a-z]{1,2}\\d?\\s*[-.\\s]\\s*\\d{3}[.\\s]?\\d{1,2}\\b") to "vehicle plate",
        Regex("\\d+[.,]\\d+\\s*(?:t|m|tan)\\b|\\d+\\s*(?:km/h|kmh|tan)\\b") to "traffic-sign value",
        Regex("\\d{1,3}(?:[.,]\\d{3})+|\\d+\\s*(?:k|vnd|dong|trieu|tr|nghin|ngan|usd)\\b|\\d+\\s*%") to "price",
    )
    private val YEAR_WORDS = Regex("\\b(?:est|since|tu nam|thanh lap|from)\\b")
    private val DATE_WORDS = Regex("\\b(?:ngay|thang|mung|chuc mung|khai truong|ap dung|den|hsd|nsx|le)\\b")
    private val HOLIDAYS = setOf("1/1", "14/2", "8/3", "30/4", "1/5", "1/6", "2/9", "20/10", "20/11", "24/12", "25/12")
    // the other lines of a plate: street, ward, district, city, old number (not "pho": phố and phở fold alike)
    private val ADDRESS_WORDS = Regex("\\b(?:duong|phuong|p\\.|quan|q\\.|tp|hcm|hem|kp|khu pho|so cu|so moi|ward|street|xa|huyen|tinh|ap)\\b")
    private val PHONE_PIECE = Regex("^0\\d{2,}$|^\\d{3,4}[\\s.]\\d{3}(?:[\\s.]\\d{3})?$")
    private val PLATE_SECOND_LINE = Regex("^\\d{3}[.,]\\d{2}$")
    private val PLATE_SERIES = Regex("^\\d{2}[A-Z]\\d?$")
    private const val LOW_IN_PHOTO = 0.62
    private val LEAD = Regex("^k?(\\d+)")
    private val SUFFIX = Regex("^k?\\d+([a-z])")
    private val DAY_MONTH = Regex("^\\d{1,2}/\\d{1,2}$")
    private val SPACES = Regex("\\s+")

    /** The number in the ride's road name ("Đường số 18", "Đường 16", "Đường Số 7"), or null. */
    fun streetNumber(roadName: String?): String? =
        roadName?.let { Regex("^\\s*(?:duong|d\\.)\\s*(?:so\\s*)?(\\d{1,3})\\b").find(fold(it))?.groupValues?.get(1) }

    /**
     * Every number on the photo's lines, classified, the likely house numbers first (plate before sign, then the
     * smaller text), each number once. [photoH]: the photo's height as the boxes see it (upright); [roadName]: the
     * road being ridden.
     */
    fun read(lines: List<Line>, photoH: Int, roadName: String?): List<Hint> {
        val street = streetNumber(roadName)
        val blocks = lines.filter { it.block >= 0 }.groupBy { it.block }
        val found = lines.flatMap { l -> classify(l, blocks[l.block].orEmpty().filter { it !== l }, photoH, street) }
        val sorted = found.sortedWith(compareBy<Hint>({ it.kind.ordinal }, { if (it.src == "plate") 0 else 1 }, { it.hFrac ?: 1.0 }))
            .distinctBy { it.text to it.kind }
        return sorted.filter { h -> sorted.none { it.text == h.text && it.kind.ordinal < h.kind.ordinal } }
    }

    fun classify(line: Line, blockMates: List<Line>, photoH: Int, street: String?): List<Hint> {
        val raw = line.text.trim()
        if (raw.isEmpty()) return emptyList()
        val f = norm(raw)
        val box = line.box?.takeIf { it.size == 4 }
        val h = box?.let { it[3] - it[1] } ?: 0
        val hFrac = if (box != null && photoH > 0) round3(h.toDouble() / photoH) else null
        val yFrac = if (box != null && photoH > 0) round3((box[1] + box[3]) / 2.0 / photoH) else null
        val mates = blockMates.map { norm(it.text) }.filter { it.isNotEmpty() }
        // a plate: the number is the biggest text of its block, and the rest (if any) is its street / ward line
        val plateLike = mates.size <= 2 && mates.all { m -> ADDRESS_WORDS.containsMatchIn(m) || m.none { it.isLetter() } } &&
            blockMates.none { o -> o.box != null && box != null && o.box[3] - o.box[1] > 1.2 * h }
        fun hint(n: String, kind: Kind, why: String?, alone: Boolean): Hint {
            val t = n.uppercase().replace(" ", "")
            // "51B" alone, low in the photo: a motorbike plate's first line whose second line was not read (house
            // plates hang at door height; the lower part of a sideways photo is the kerb with its parked bikes)
            val (k, w) = if (kind == Kind.NUMBER && PLATE_SERIES.matches(t) && (yFrac ?: 0.0) >= LOW_IN_PHOTO)
                Kind.DOUBT to "2 digits and a letter low in the photo (vehicle plate?)" else kind to why
            return Hint(t, k, w, raw, box, h, hFrac, yFrac, if (alone && plateLike) "plate" else "sign")
        }

        val named = mutableListOf<Hint>()
        var rest = f
        for (nm in NAMED) {
            if (nm.why in RECORDED) nm.re.findAll(rest).forEach { named += hint(it.groupValues[1], Kind.DROP, nm.why, false) }
            rest = norm(nm.re.replace(rest, nm.keep))
        }
        val out = mutableListOf<Hint>()
        OLD.find(rest)?.let { m ->
            out += hint(m.groupValues[1], Kind.DOUBT, "old number (số cũ)", false)
            rest = norm(rest.replace(m.value, " ~ "))
        }
        val alone = ALONE.find(rest)
        val m = alone ?: KEYWORD.find(rest) ?: BEFORE_STREET.find(rest) ?: LINE_START.find(rest)
            ?: return out + named                   // e.g. "Đường số 18" alone: its number is recorded as dropped
        val n = m.groupValues[1]
        val start = m.range.first + m.value.indexOf(n)
        val (kind, why) = judge(n.replace(" ", ""), rest, start until start + n.length, mates, street, alone != null)
        out += hint(n, kind, why, alone != null)
        return out                                  // the line gave a number: the street name on it is no news
    }

    private fun norm(s: String) = fold(s).replace(SPACES, " ").trim()

    /** Is [n], found at [span] of the folded line [f] (named numbers blanked), a house number? */
    private fun judge(n: String, f: String, span: IntRange, mates: List<String>, street: String?, alone: Boolean): Pair<Kind, String?> {
        val lead = LEAD.find(n)!!.groupValues[1]
        val suffix = SUFFIX.find(n)?.groupValues?.get(1)
        val compound = n.contains('/') || n.contains('-')
        val context = (listOf(f) + mates).joinToString(" | ")
        SPANS.firstOrNull { (re, _) -> re.findAll(f).any { it.range.first <= span.last && span.first <= it.range.last } }
            ?.let { return Kind.DROP to it.second }
        return when {
            lead.all { it == '0' } -> Kind.DROP to "zeros (a display or a code)"
            lead.length >= 5 -> Kind.DROP to "5+ digits (phone, account or code)"
            suffix == "k" -> Kind.DROP to "price"
            lead.length >= 3 && lead.startsWith("0") && !compound -> Kind.DROP to "phone fragment (leading 0)"
            mates.any { PHONE_PIECE.matches(it) } && lead.length >= 3 && !compound -> Kind.DROP to "phone fragment (next to one)"
            mates.any { PLATE_SECOND_LINE.matches(it) } && !compound -> Kind.DROP to "vehicle plate"
            lead.length == 4 && suffix != null -> Kind.DROP to "4 digits and a letter (a code or a misread price)"
            lead.length == 4 && lead.toInt() in 1900..2099 && YEAR_WORDS.containsMatchIn(context) -> Kind.DROP to "year"
            lead.length == 4 && lead.toInt() in 1900..2099 -> Kind.DOUBT to "year?"
            lead.length == 4 -> Kind.DOUBT to "4 digits (rare for a house number)"
            street != null && !compound && suffix == null && lead == street -> Kind.DOUBT to "the street's own number (Đường số $street)"
            suffix != null && suffix !in "abcd" -> Kind.DOUBT to "letter ${suffix.uppercase()} (vehicle plate series?)"
            lead.length == 2 && lead.startsWith("0") && !compound -> Kind.DOUBT to "leading 0"
            DAY_MONTH.matches(n) && (n in HOLIDAYS || DATE_WORDS.containsMatchIn(context)) -> Kind.DOUBT to "date?"
            !alone && YEAR_WORDS.containsMatchIn(f) && lead.length >= 2 -> Kind.DOUBT to "year or brand number?"
            else -> Kind.NUMBER to null
        }
    }

    private fun round3(v: Double) = kotlin.math.round(v * 1000) / 1000
}

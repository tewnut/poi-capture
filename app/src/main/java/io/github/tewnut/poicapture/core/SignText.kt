package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** One line of text read on a photo: its text and its height in pixels (bigger = more prominent on the sign). */
data class TextLine(val text: String, val heightPx: Int, val box: IntArray? = null)

/**
 * What the phone could read on the photos of a place: hints for the server's agent, which decides (name, category,
 * address, dedup against the map). Shown to the contributor read-only, mainly to say "this sign photo is readable".
 */
data class SignReading(
    val lines: List<TextLine>,
    val name: String?,
    val phones: List<String>,
    val houseNumber: String?,
    val category: String?,
) {
    val empty get() = lines.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("engine", "mlkit-latin")
        .put("text", lines.joinToString("\n") { it.text })
        .put("lines", JSONArray().apply {
            lines.forEach { l ->
                put(JSONObject().put("text", l.text).put("heightPx", l.heightPx)
                    .apply { l.box?.let { put("box", JSONArray(it.toList())) } })
            }
        })
        .put("name", name ?: JSONObject.NULL)
        .put("phones", JSONArray(phones))
        .put("houseNumber", houseNumber ?: JSONObject.NULL)
        .put("category", category ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): SignReading? {
            o ?: return null
            val a = o.optJSONArray("lines") ?: JSONArray()
            return SignReading(
                (0 until a.length()).map { a.getJSONObject(it).let { l ->
                    TextLine(l.optString("text"), l.optInt("heightPx"),
                        l.optJSONArray("box")?.takeIf { it.length() == 4 }?.let { b -> IntArray(4) { k -> b.getInt(k) } })
                } },
                o.optString("name").takeIf { it.isNotBlank() && it != "null" },
                o.optJSONArray("phones")?.let { p -> (0 until p.length()).map { p.getString(it) } } ?: emptyList(),
                o.optString("houseNumber").takeIf { it.isNotBlank() && it != "null" },
                o.optString("category").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}

/**
 * Rules over OCR text of Vietnamese shop signs. Matching works on folded text (no diacritics, lowercase), so it still
 * works when the OCR drops a tone mark ("pho" matches "phở").
 */
object SignText {
    /** Lowercase, without diacritics, đ → d. */
    fun fold(s: String): String = Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase()

    // 0/+84 then 9–10 digits, with spaces, dots or dashes between groups; hotlines 1800 / 1900
    private val PHONE = Regex("(?<![\\d])(?:\\+?84|0)(?:[\\s.\\-]?\\d){8,10}(?![\\d])|(?<![\\d])1[89]00(?:[\\s.\\-]?\\d){4,6}(?![\\d])")

    /** Vietnamese phone numbers in the text, digits only (+84 → 0), most likely first, without duplicates. */
    fun phones(text: String): List<String> = PHONE.findAll(text).map { m ->
        val d = m.value.filter { it.isDigit() }
        if (d.startsWith("84") && m.value.trim().let { it.startsWith("+") || it.startsWith("84") }) "0" + d.drop(2) else d
    }.filter { p ->
        (p.startsWith("0") && (p.length == 10 || (p.length == 11 && p.startsWith("02")))) || p.startsWith("1800") || p.startsWith("1900")
    }.distinct().toList()

    private val STREET = "(?:pho|duong|ngo|ngach|hem|kiet|p\\.|d\\.|đ\\.|q\\.|to|khu|thon|xom|ql|tl)"
    private val HOUSE_KEYWORD = Regex("(?:^|\\b)(?:so nha|so|sn|dia chi|dc|d/c|add|address)[:.\\s]+(\\d{1,4}[a-z]?(?:/\\d{1,4}[a-z]?){0,3})\\b")
    private val HOUSE_BEFORE_STREET = Regex("(?:^|\\s)(\\d{1,4}[a-z]?(?:/\\d{1,4}[a-z]?){0,3})\\s*,?\\s+$STREET\\b")
    // "102 Hàng Bông": a number opening the line, then at least two words (a street name)
    private val HOUSE_LINE_START = Regex("^\\s*(\\d{1,4}[a-z]?(?:/\\d{1,4}[a-z]?){0,3})\\s+[a-z]{2,}\\s+[a-z]{2,}")

    /** House number ("12", "12A", "45/3", "102/7B") written next to "Số", "SN", "ĐC" or before a street word. */
    fun houseNumber(text: String): String? {
        for (line in text.lines()) {
            val f = fold(line)
            if (PHONE.containsMatchIn(line) && !f.contains("dc") && !f.contains("dia chi") && !f.contains("so ")) continue
            HOUSE_KEYWORD.find(f)?.let { return it.groupValues[1].uppercase() }
            HOUSE_BEFORE_STREET.find(f)?.let { return it.groupValues[1].uppercase() }
            HOUSE_LINE_START.find(f)?.let { return it.groupValues[1].uppercase() }
        }
        return null
    }

    /**
     * Category keywords (folded; Vietnamese, as written on signs) per stable category id. The id is exported
     * (`signRead.category`) and the UI shows a translated label for it. The first category whose keyword appears wins,
     * in this order.
     */
    val CATEGORY_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "pharmacy" to listOf("nha thuoc", "quay thuoc", "pharmacy", "duoc pham", "thuoc tay"),
        "cafe" to listOf("ca phe", "cafe", "caphe", "coffee", "cofe"),
        "drinks" to listOf("tra sua", "milk tea", "tra chanh", "nuoc mia", "sinh to", "nuoc ep", "bubble tea"),
        "restaurant" to listOf("pho", "bun", "com", "banh mi", "lau", "nuong", "quan an", "mi quang", "hu tieu", "chao", "mien",
            "banh cuon", "xoi", "oc", "ga ran", "bit tet", "an vat", "nha hang", "restaurant", "quan nhau", "bia hoi", "kitchen", "food"),
        "grocery" to listOf("tap hoa", "bach hoa", "minimart", "mini mart", "sieu thi", "cua hang tien loi", "mart"),
        "vehicle_repair" to listOf("sua xe", "rua xe", "va lop", "va xe", "thay nhot", "phu tung", "garage", "gara"),
        "phone_shop" to listOf("dien thoai", "mobile", "phu kien", "sua dien thoai", "sim so", "smartphone"),
        "beauty" to listOf("salon", "cat toc", "toc", "nail", "spa", "my pham", "lam dep", "barber", "goi dau", "massage"),
        "fashion" to listOf("thoi trang", "fashion", "quan ao", "giay dep", "shop", "boutique", "may do", "tui xach"),
        "hotel" to listOf("khach san", "nha nghi", "hotel", "homestay", "motel", "hostel"),
        "bank" to listOf("ngan hang", "bank", "atm"),
        "education" to listOf("trung tam", "tieng anh", "english", "hoc", "truong", "mam non", "academy"),
        "health" to listOf("phong kham", "nha khoa", "benh vien", "clinic", "dental", "xet nghiem"),
        "electronics" to listOf("dien may", "dien tu", "dien lanh", "may tinh", "laptop", "camera"),
        "hardware" to listOf("vat lieu", "kim khi", "son ", "gach", "thiet bi ve sinh", "dien nuoc"),
    )

    fun category(text: String): String? {
        val f = " " + fold(text).replace(Regex("[^a-z0-9]+"), " ") + " "
        return CATEGORY_KEYWORDS.firstOrNull { (_, kws) -> kws.any { k -> f.contains(" ${k.trim()} ") } }?.first
    }

    /** Brands of drink / telecom sponsors printed on many signboards: big text, but not the shop's name. */
    private val SPONSORS = listOf("coca cola", "cocacola", "pepsi", "heineken", "tiger", "bia ha noi", "bia saigon", "sabeco",
        "habeco", "larue", "333", "viettel", "vinaphone", "mobifone", "vietnamobile", "sting", "redbull", "red bull", "number 1",
        "nescafe", "g7", "trung nguyen", "omo", "unilever", "momo", "zalopay", "vnpay", "grab", "shopeefood", "be ", "gojek",
        "hotline", "dien thoai", "dt", "tel", "dc", "dia chi", "address")

    /** Notices that are not the shop's name: for rent / for sale / contact ("U CHO THUẾ", "LH: 0985…"). */
    private val NOTICE = Regex("\\b(?:cho thue|sang nhuong|sang quan|can ban|ban nha|for rent|for lease|lien he|lh|giay phep|dkkd)\\b")

    /**
     * Text read across its line: a line whose box is much taller than wide is text running down a wall or pillar,
     * read sideways by the OCR ("IIHETLIIIII"), and its height is its length, not its letter size.
     */
    private fun upright(l: TextLine): Boolean = l.box?.let { b -> (b[3] - b[1]) <= 1.5 * (b[2] - b[0]) } ?: true

    /**
     * The shop's name: the tallest line that has letters, is not a phone / address / sponsor / for-rent line and
     * is not text read sideways. A guess; the agent checks it against the other photos and the map.
     */
    fun name(lines: List<TextLine>): String? = lines
        .filter { l ->
            val f = fold(l.text).replace(Regex("[^a-z0-9/]+"), " ").trim()
            f.count { it.isLetter() } >= 3 &&
                upright(l) && !NOTICE.containsMatchIn(f) &&
                !PHONE.containsMatchIn(l.text) &&
                SPONSORS.none { s -> f == s.trim() || f.startsWith(s) } &&
                HOUSE_KEYWORD.find(f) == null && HOUSE_BEFORE_STREET.find(f) == null && HOUSE_LINE_START.find(f) == null
        }
        .maxByOrNull { it.heightPx }
        ?.text?.trim()?.replace(Regex("\\s+"), " ")

    fun read(lines: List<TextLine>): SignReading {
        val clean = lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        val text = clean.joinToString("\n") { it.text }
        return SignReading(clean, name(clean), phones(text), houseNumber(text), category(text))
    }

    /** Several photos of one place: name from the most prominent reading, phones merged, first category / house. */
    fun merge(readings: List<SignReading>): SignReading? {
        val r = readings.filter { !it.empty }
        if (r.isEmpty()) return null
        val all = r.flatMap { it.lines }
        return SignReading(all, name(all), r.flatMap { it.phones }.distinct(), r.firstNotNullOfOrNull { it.houseNumber },
            r.firstNotNullOfOrNull { it.category })
    }
}

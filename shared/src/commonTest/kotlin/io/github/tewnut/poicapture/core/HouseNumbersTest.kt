package io.github.tewnut.poicapture.core

import io.github.tewnut.poicapture.core.HouseNumbers.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HouseNumbersTest {
    private fun one(text: String, road: String? = null, mates: List<String> = emptyList()): HouseNumbers.Hint? {
        val lines = listOf(HouseNumbers.Line(text, intArrayOf(100, 1900, 200, 1940), 0)) +
            mates.map { HouseNumbers.Line(it, intArrayOf(100, 1950, 300, 1980), 0) }
        return HouseNumbers.read(lines, 3840, road).firstOrNull { it.line == text }
    }
    private fun kind(text: String, road: String? = null, mates: List<String> = emptyList()) = one(text, road, mates)?.kind

    @Test fun realHouseNumbersAreKept() {
        // read on plates and shop signs of the 24 Nov 2026 rides
        for (t in listOf("12", "12A", "12/3", "12/3A", "38/1", "04/9", "170-172", "558B", "99B", "694A", "317A", "K12",
                "K12/3", "309", "Số 12", "SN 7B", "106", "45/2a", "88")) {
            assertEquals(Kind.NUMBER, kind(t), t)
        }
        assertEquals(Kind.DOUBT, kind("1400"))                                 // real (Đồng Văn Cống), but 4 digits: kept as a doubt
        assertEquals("170-172", one("170 - 172")?.text)
        assertEquals("45/2A", one("45/2a")?.text)
        assertEquals("12", one("Số 12")?.text)
        assertEquals("88", one("88 Trường Sa")?.text)
        assertEquals("22", one("Số 22 Đường số 18", "Đường số 18")?.text)
        assertEquals(Kind.NUMBER, kind("Số 22 Đường số 18", "Đường số 18"))
        assertEquals("12", one("12 Đường số 18", "Đường số 18")?.text)       // the street word is kept for "before a street"
        assertEquals("45/3", one("ĐC: 45/3 Đường Lê Lợi, Q.1")?.text)
        assertEquals("102B", one("102b Hàng Bông")?.text)
        // a 3-part alley address is not a date
        assertEquals(Kind.NUMBER, kind("123/4/5"))
        assertEquals(Kind.NUMBER, kind("12/3/45"))
    }

    @Test fun pricesAreDropped() {
        for (t in listOf("45K", "299K", "55k", "200K", "50.000đ", "995.000đ", "35K", "8.390.000", "30%", "25 nghìn"))
            assertTrue(kind(t) == null || kind(t) == Kind.DROP, t)
        assertEquals("price", one("45K")?.why)
        assertEquals(Kind.DROP, kind("9950C"))                                // 995.000đ misread: 4 digits and a letter
    }

    @Test fun phonesAndTheirPiecesAreDropped() {
        for (t in listOf("0963", "0901", "0225", "0912 345 678", "01688123456", "0983J", "123456"))
            assertTrue(kind(t) == null || kind(t) == Kind.DROP, t)
        // "0963 548 3.." split into lines of one block: the piece next to a phone piece goes too
        assertEquals(Kind.DROP, kind("5483", mates = listOf("0963")))
        assertEquals("phone fragment (leading 0)", one("0963")?.why)
    }

    @Test fun datesYearsAndHoursAreNotHouseNumbers() {
        assertEquals(Kind.DROP, kind("25/11/2026"))
        assertEquals(Kind.DROP, kind("9H-13H"))
        assertEquals(Kind.DOUBT, kind("20/10"))                                // Vietnamese Women's Day poster
        assertEquals(Kind.DOUBT, kind("1976"))
        assertEquals(Kind.DROP, kind("1976", mates = listOf("EST")))           // "EST 1976" on a sign
        assertEquals(Kind.DOUBT, kind("2026"))
    }

    @Test fun vehiclePlatesAndCodesAreDroppedOrDoubted() {
        assertNull(kind("51B-403.79"))                                         // no house number in it at all
        assertEquals(Kind.DROP, kind("51B", mates = listOf("403.79")))         // a motorbike plate's two lines
        for (t in listOf("50H", "73H", "29E", "58L", "13H", "1G")) assertEquals(Kind.DOUBT, kind(t), t)
        assertEquals(Kind.DOUBT, kind("25T"))                                  // 2.5T with the dot lost
        assertNull(kind("2.5T"))
        assertNull(kind("3.5 m"))
        assertNull(kind("T76"))                                                // pole labels
        assertNull(kind("X022 T9C"))
        assertEquals(Kind.DROP, kind("0000"))                                  // fuel-price display
        assertEquals(Kind.DROP, kind("000"))
        assertEquals(Kind.DOUBT, kind("4071"))
    }

    @Test fun aPlateSeriesLowInThePhotoIsDoubted() {
        fun at(text: String, y: Int) = HouseNumbers.read(listOf(HouseNumbers.Line(text, intArrayOf(100, y, 200, y + 40), 0)), 3840, null)
            .firstOrNull()?.kind
        assertEquals(Kind.DOUBT, at("51B", 3000))      // at the kerb: a bike's plate, second line not read
        assertEquals(Kind.NUMBER, at("51B", 1200))     // at door height: house 51B
        assertEquals(Kind.NUMBER, at("12", 3000))      // plain numbers are not plate series
    }

    @Test fun theStreetsOwnNumberIsNotAHouseNumber() {
        // "Đường số 18" on every sign of the ride: dropped, with the reason
        val h = one("ĐƯỜNG SỐ 18", "Đường số 18")!!
        assertEquals(Kind.DROP, h.kind)
        assertEquals("street name", h.why)
        assertEquals("18", h.text)
        assertEquals(Kind.DROP, kind("Đường 16"))
        assertEquals(Kind.DROP, kind("Cơ sở 2"))
        // a bare 18 on Đường số 18 may be the street sign or a real number 18 (there is one, on a gate): doubt
        assertEquals(Kind.DOUBT, kind("18", "Đường số 18"))
        assertEquals(Kind.DOUBT, kind("Số 7", "Đường Số 7"))
        assertEquals(Kind.NUMBER, kind("18", "Lê Văn Thịnh"))
        assertEquals("18", HouseNumbers.streetNumber("Đường số 18"))
        assertEquals("7", HouseNumbers.streetNumber("Đường Số 7"))
        assertEquals("61", HouseNumbers.streetNumber("Đường số 61-TML"))
        assertNull(HouseNumbers.streetNumber("Nguyễn Thị Định"))
        assertEquals(Kind.DOUBT, kind("Số cũ 7/12"))
    }

    @Test fun plateOrSignAndWhereOnThePhoto() {
        val plate = HouseNumbers.read(listOf(
            HouseNumbers.Line("38/1", intArrayOf(500, 1500, 620, 1560), 3),
            HouseNumbers.Line("Đường số 18, P. Cát Lái", intArrayOf(480, 1570, 700, 1590), 3),
            HouseNumbers.Line("PHỞ HÀ NỘI", intArrayOf(0, 300, 2000, 700), 1),
            HouseNumbers.Line("106", intArrayOf(800, 720, 1100, 900), 1),
        ), 3840, "Đường số 18")
        val p = plate.first { it.text == "38/1" }
        assertEquals("plate", p.src)
        assertEquals(0.016, p.hFrac!!, 1e-9)
        assertEquals(0.398, p.yFrac!!, 1e-9)
        assertEquals("sign", plate.first { it.text == "106" }.src)
        // likely numbers first, a plate before a sign
        assertEquals(listOf("38/1", "106"), plate.filter { it.kind == Kind.NUMBER }.map { it.text })
        assertEquals(Kind.DROP, plate.first { it.text == "18" }.kind)
    }

    @Test fun eachNumberOnce() {
        val r = HouseNumbers.read(listOf(HouseNumbers.Line("88 Trường Sa"), HouseNumbers.Line("88"), HouseNumbers.Line("CÀ PHÊ"),
            HouseNumbers.Line("0"), HouseNumbers.Line("Phở Hà Nội"), HouseNumbers.Line("6:00 - 22:00")), 3840, null)
        assertEquals(listOf("88"), r.filter { it.kind == Kind.NUMBER }.map { it.text })
        assertTrue(r.none { it.text == "0" && it.kind != Kind.DROP })
    }

    @Test fun foldsVietnamese() {
        assertEquals("duong so 18 phuong cat lai", HouseNumbers.fold("Đường Số 18 Phường Cát Lái"))
        assertEquals("pho", HouseNumbers.fold("PHỞ"))
    }
}

package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignTextTest {
    @Test fun phones() {
        assertEquals(listOf("0912345678"), SignText.phones("ĐT: 0912 345 678"))
        assertEquals(listOf("0912345678"), SignText.phones("Hotline 0912.345.678 - 0912-345-678"))
        assertEquals(listOf("0912345678"), SignText.phones("+84 912 345 678"))
        assertEquals(listOf("02438251234"), SignText.phones("Tel 024 3825 1234"))
        assertEquals(listOf("19001234"), SignText.phones("Tổng đài 1900 1234"))
        assertTrue(SignText.phones("Số 12 Phố Huế, năm 2024").isEmpty())
        assertTrue(SignText.phones("12345678901234").isEmpty())
    }

    @Test fun houseNumbers() {
        assertEquals("12", SignText.houseNumber("Số 12 Phố Huế"))
        assertEquals("45/3", SignText.houseNumber("ĐC: 45/3 Đường Lê Lợi, Q.1"))
        assertEquals("102B", SignText.houseNumber("102b Hàng Bông"))
        assertEquals("7", SignText.houseNumber("SN 7, ngõ 12"))
        assertNull(SignText.houseNumber("PHỞ THÌN\nĐT 0912345678"))
    }

    @Test fun categoriesSurviveLostDiacritics() {
        assertEquals("restaurant", SignText.category("PHỞ BÒ GIA TRUYỀN"))
        assertEquals("restaurant", SignText.category("PHO BO GIA TRUYEN"))
        assertEquals("cafe", SignText.category("Cafe Giảng"))
        assertEquals("pharmacy", SignText.category("NHÀ THUỐC LONG CHÂU"))
        assertEquals("grocery", SignText.category("Tạp Hoá Minh Anh"))
        assertEquals("vehicle_repair", SignText.category("SỬA XE - VÁ LỐP"))
        assertNull(SignText.category("Minh Anh"))
        // words, not substrings: "bun" is not in "bunny"
        assertNull(SignText.category("Bunny house"))
    }

    @Test fun nameIsTheTallestLineThatIsNotASponsorOrContact() {
        val lines = listOf(
            TextLine("Coca-Cola", 220),
            TextLine("PHỞ THÌN", 180),
            TextLine("Gia truyền từ 1955", 60),
            TextLine("ĐT: 0912 345 678", 90),
            TextLine("Số 13 Lò Đúc", 100),
        )
        val r = SignText.read(lines)
        assertEquals("PHỞ THÌN", r.name)
        assertEquals(listOf("0912345678"), r.phones)
        assertEquals("13", r.houseNumber)
        assertEquals("restaurant", r.category)
        // round trip through the export JSON
        val back = SignReading.fromJson(r.toJson())!!
        assertEquals(r.name, back.name)
        assertEquals(r.phones, back.phones)
        assertEquals(5, back.lines.size)
    }

    @Test fun nameSkipsSidewaysTextAndForRentNotices() {
        // from the 11 Nov place capture on Mai Chí Thọ
        val norman = listOf(
            TextLine("IIHETLIIIII", 562, intArrayOf(128, 1554, 200, 2116)),       // a pillar, read sideways
            TextLine("SHUTTERS.BUINDS.SHADE.", 270, intArrayOf(210, 1086, 952, 1356)),
            TextLine("NORMAN", 36, intArrayOf(1176, 2016, 1272, 2052)))
        assertEquals("SHUTTERS.BUINDS.SHADE.", SignText.name(norman))
        val phucLong = listOf(
            TextLine("U CHO THUẾ", 122, intArrayOf(-2, 726, 220, 848)),
            TextLine("PHUC LONG", 84, intArrayOf(708, 1320, 1022, 1404)),
            TextLine("LH: 098599936", 66))
        assertEquals("PHUC LONG", SignText.name(phucLong))
        // boxes survive the round trip, so merging several photos still skips sideways text
        val back = SignReading.fromJson(SignText.read(norman).toJson())!!
        assertEquals("SHUTTERS.BUINDS.SHADE.", SignText.merge(listOf(back))?.name)
    }

    @Test fun mergesThePhotosOfAPlace() {
        val front = SignText.read(listOf(TextLine("TẠP HOÁ MINH ANH", 150)))
        val info = SignText.read(listOf(TextLine("Mở cửa 6h - 22h", 40), TextLine("ĐT 0987 654 321", 50)))
        val m = SignText.merge(listOf(front, SignText.read(emptyList()), info))!!
        assertEquals("TẠP HOÁ MINH ANH", m.name)
        assertEquals(listOf("0987654321"), m.phones)
        assertEquals("grocery", m.category)
        assertNull(SignText.merge(listOf(SignText.read(emptyList()))))
    }
}

package io.github.tewnut.poicapture.ui.settings

import io.github.tewnut.poicapture.net.RoadSource
import io.github.tewnut.poicapture.ui.settings.SettingsLogic.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    private fun entry(id: String, name: String, lat: Double = 16.0, version: String = "2025-11-20", bytes: Long = 1L) =
        RoadSource.Entry(id, name, version, bytes, "", "https://x/$id.zip", lat - 0.5, 105.0, lat + 0.5, 106.0)

    @Test fun foldDropsAccentsAndD() {
        assertEquals("thu duc", SettingsLogic.fold("Thủ  Đức"))
        assertEquals("tp ho chi minh", SettingsLogic.fold("TP. Hồ Chí Minh"))
        assertEquals("dak lak", SettingsLogic.fold("Đắk Lắk"))
        assertEquals("hue", SettingsLogic.fold("Huế"))
    }

    @Test fun searchIgnoresAccentsAndCase() {
        assertTrue(SettingsLogic.matches("Thủ Đức", "thu-duc", "thu duc"))
        assertTrue(SettingsLogic.matches("Thủ Đức", "thu-duc", "THỦ đức"))
        assertTrue(SettingsLogic.matches("TP. Hồ Chí Minh", "tp-hcm", "hcm"))
        assertTrue(SettingsLogic.matches("Đà Nẵng", "da-nang", "nang"))
        assertTrue(SettingsLogic.matches("Đà Nẵng", "da-nang", "  "))
        assertFalse(SettingsLogic.matches("Đà Nẵng", "da-nang", "ha noi"))
        assertFalse(SettingsLogic.matches("Hà Nội", "ha-noi", "ha nam"))
    }

    @Test fun zonesByKnownIdThenLatitude() {
        assertEquals(Zone.NORTH, SettingsLogic.zoneOf("ha-noi", 21.0))
        assertEquals(Zone.CENTRAL, SettingsLogic.zoneOf("thanh-hoa", 20.0))
        assertEquals(Zone.CENTRAL, SettingsLogic.zoneOf("lam-dong", 11.5))
        assertEquals(Zone.SOUTH, SettingsLogic.zoneOf("tp-hcm", 10.8))
        assertEquals(Zone.NORTH, SettingsLogic.zoneOf("somewhere-new", 22.0))
        assertEquals(Zone.CENTRAL, SettingsLogic.zoneOf("somewhere-new", 15.0))
        assertEquals(Zone.SOUTH, SettingsLogic.zoneOf("somewhere-new", 9.5))
    }

    @Test fun groupsNorthToSouthSortedAndFiltered() {
        val list = listOf(entry("tp-hcm", "TP. Hồ Chí Minh", 10.8), entry("hai-phong", "Hải Phòng", 20.8),
            entry("ha-noi", "Hà Nội", 21.0), entry("da-nang", "Đà Nẵng", 16.0), entry("an-giang", "An Giang", 10.4))
        val g = SettingsLogic.group(list)
        assertEquals(listOf(Zone.NORTH, Zone.CENTRAL, Zone.SOUTH), g.map { it.first })
        assertEquals(listOf("ha-noi", "hai-phong"), g[0].second.map { it.id })
        assertEquals(listOf("an-giang", "tp-hcm"), g[2].second.map { it.id })
        val found = SettingsLogic.group(list, "hai phong")
        assertEquals(1, found.size)
        assertEquals(Zone.NORTH, found[0].first)
        assertEquals(listOf("hai-phong"), found[0].second.map { it.id })
        assertTrue(SettingsLogic.group(list, "xyz").isEmpty())
    }

    @Test fun storageAndSizes() {
        val s = SettingsLogic.storage(listOf(50L * 1048576, 69L * 1048576, 1024L * 1024))
        assertEquals(3, s.count)
        assertEquals("120 MB", SettingsLogic.formatBytes(s.bytes))
        assertEquals(SettingsLogic.Storage(0, 0), SettingsLogic.storage(emptyList()))
        assertEquals("512 KB", SettingsLogic.formatBytes(512L * 1024))
        assertEquals("12.3 MB", SettingsLogic.formatBytes((12.3 * 1048576).toLong()))
        assertEquals("1.5 GB", SettingsLogic.formatBytes(1536L * 1048576))
    }

    @Test fun updatesOnlyForInstalledWithAnotherVersion() {
        val cat = listOf(entry("ha-noi", "Hà Nội", version = "2025-12-01"), entry("hue", "Huế", version = "2025-11-20"),
            entry("tp-hcm", "TP. Hồ Chí Minh", version = "2025-12-01"))
        val u = SettingsLogic.updates(mapOf("ha-noi" to "2025-11-20", "hue" to "2025-11-20"), cat)
        assertEquals(listOf("ha-noi"), u.map { it.id })
    }

    @Test fun masksEmailsOnly() {
        assertEquals("n•••@gmail.com", SettingsLogic.maskContributor("nguyen.van.a@gmail.com"))
        assertEquals("HCM-024", SettingsLogic.maskContributor("HCM-024"))
    }
}

package io.github.tewnut.poicapture

import io.github.tewnut.poicapture.core.FieldTask
import io.github.tewnut.poicapture.core.FieldTaskStore
import io.github.tewnut.poicapture.core.FieldTasks
import io.github.tewnut.poicapture.core.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FieldTasksTest {
    private val export = """{"type":"FeatureCollection","schema":"poi-tasks/1","features":[
      {"type":"Feature","geometry":{"type":"Point","coordinates":[106.75,10.77]},
       "properties":{"id":"s1-task-0","type":"visit","name":"Kem Ý","why":"sign unreadable","place":"s1-01","session":"s1"}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":[106.76,10.78]},
       "properties":{"id":"s1-task-1","type":"removal","name":"Old shop","why":"for rent","place":null,"session":"s1"}},
      {"type":"Feature","geometry":{"type":"LineString","coordinates":[[1,2],[3,4]]},"properties":{"id":"x","type":"visit"}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":[106.7,10.7]},"properties":{"id":"y","type":"something"}}
    ]}"""

    @Test fun parsesTheReviewerExport() {
        val t = FieldTasks.parse(export)
        assertEquals(listOf("s1-task-0", "s1-task-1"), t.map { it.id })
        assertEquals(LatLon(10.77, 106.75), t[0].at)
        assertEquals("s1-01", t[0].place)
        assertNull(t[1].place)
        assertEquals("verify_removal", t[1].type)    // a removal sent to the field is a check on site
        assertTrue(t.all { it.open })
    }

    @Test(expected = FieldTasks.BadFile::class) fun refusesOtherFiles() { FieldTasks.parse("""{"type":"FeatureCollection","schema":"poi-result/1","features":[]}""") }

    @Test(expected = FieldTasks.BadFile::class) fun refusesNonJson() { FieldTasks.parse("PK\u0003\u0004 zip") }

    @Test fun reimportKeepsWhatWasDone() {
        val first = FieldTasks.parse(export).map { if (it.id == "s1-task-0") it.copy(state = FieldTask.DONE, doneBy = "poi_1 in s_2") else it }
        val again = FieldTasks.merge(first, FieldTasks.parse(export))
        assertEquals(FieldTask.DONE, again[0].state)
        assertEquals("poi_1 in s_2", again[0].doneBy)
        assertTrue(again[1].open)
    }

    @Test fun nearestOpenWithin25m() {
        val t = FieldTasks.parse(export)
        assertEquals("s1-task-0", FieldTasks.nearestOpen(t, LatLon(10.77010, 106.75005))?.id)   // ~12 m
        assertNull(FieldTasks.nearestOpen(t, LatLon(10.7710, 106.75)))                         // ~110 m
        assertNull(FieldTasks.nearestOpen(t.map { it.copy(state = FieldTask.SKIPPED) }, LatLon(10.77, 106.75)))
    }

    @Test fun storeRoundTrip() {
        val f = File.createTempFile("tasks", ".json").apply { delete() }
        val s = FieldTaskStore(f)
        assertEquals(2, s.import(export).size)
        s.set("s1-task-1", FieldTask.SKIPPED, "2026-11-16T08:00:00Z", "contributor")
        val back = FieldTaskStore(f).all()
        assertEquals(FieldTask.SKIPPED, back[1].state)
        assertEquals("2026-11-16T08:00:00Z", back[1].doneAt)
        assertEquals("Kem Ý", back[0].name)
        s.clear()
        assertTrue(s.all().isEmpty())
    }
}

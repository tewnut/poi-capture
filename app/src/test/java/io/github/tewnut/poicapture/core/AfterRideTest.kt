package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** After a ride: upload decisions (UploadRules), the upload record's new fields, field tasks of a deleted ride. */
class AfterRideTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roomNeededIsTheRidePlusAMargin() {
        assertEquals(UploadRules.SPARE_BYTES, UploadRules.roomNeeded(0))
        val gb = 1L shl 30
        assertEquals(gb + gb / 20 + UploadRules.SPARE_BYTES, UploadRules.roomNeeded(gb))
    }

    @Test fun authIsTheServersRefusalNotAnExpiredPartLink() {
        assertTrue(UploadRules.isAuth(401, "direct: HTTP 401"))
        assertTrue(UploadRules.isAuth(403, "direct complete: HTTP 403"))
        assertTrue(UploadRules.isAuth(401, null))
        assertFalse(UploadRules.isAuth(403, "part: HTTP 403"))   // R2: presigned URL expired
        assertFalse(UploadRules.isAuth(404, "direct: HTTP 404"))
        assertFalse(UploadRules.isAuth(500, "direct: HTTP 500"))
    }

    @Test fun noSpaceIsRecognised() {
        assertTrue(UploadRules.isNoSpace("write failed: ENOSPC (No space left on device)"))
        assertTrue(UploadRules.isNoSpace("No space left on device"))
        assertFalse(UploadRules.isNoSpace("timeout"))
        assertFalse(UploadRules.isNoSpace(null))
    }

    @Test fun givesUpOnPermanentOrAfterMaxFailures() {
        assertTrue(UploadRules.giveUp(permanent = true, failures = 1))
        assertFalse(UploadRules.giveUp(permanent = false, failures = UploadRules.MAX_FAILURES - 1))
        assertTrue(UploadRules.giveUp(permanent = false, failures = UploadRules.MAX_FAILURES))
    }

    @Test fun uploadRecordKeepsFailuresAndProblem() {
        val r = UploadRecord("s_1", UploadStatus.FAILED, error = "direct: HTTP 401", failures = 3, problem = UploadRecord.AUTH)
        val back = UploadRecord.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(r, back)
        assertTrue(back.authFailed)
        assertFalse(back.noSpace)
        // a record written before these fields
        val old = UploadRecord.fromJson(JSONObject().put("sessionId", "s_2").put("status", "queued"))
        assertEquals(0, old.failures)
        assertNull(old.problem)
        assertFalse(old.authFailed)
    }

    private fun task(id: String, state: String = FieldTask.OPEN, by: String? = null) =
        FieldTask(id, "visit", id, "", LatLon(10.0, 106.0), null, null, state, if (by != null) "2026-11-01T00:00:00Z" else null, by)

    @Test fun reopenByOpensOnlyTheTasksThatRideDid() {
        val tasks = listOf(
            task("a", FieldTask.DONE, "poi_1 in s_1"),
            task("b", FieldTask.DONE, "poi_2 in s_2"),
            task("c", FieldTask.DONE, "contributor"),
            task("d", FieldTask.SKIPPED, "contributor"),
            task("e", FieldTask.DONE, "poi_3 in s_10"),   // another ride whose id ends the same way
            task("f"),
        )
        val out = FieldTasks.reopenBy(tasks, "s_1")
        assertEquals(FieldTask(id = "a", type = "visit", name = "a", why = "", at = LatLon(10.0, 106.0), place = null, session = null), out[0])
        assertEquals(tasks.drop(1), out.drop(1))
    }

    @Test fun storeReopenByRewritesOnlyWhenSomethingChanged() {
        val f = tmp.newFile("tasks.json").apply { delete() }
        val store = FieldTaskStore(f)
        store.save(listOf(task("a", FieldTask.DONE, "poi_1 in s_1"), task("b")))
        assertEquals(0, store.reopenBy("s_9"))
        assertEquals(1, store.reopenBy("s_1"))
        assertTrue(store.all().all { it.open })
    }
}

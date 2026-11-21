package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SetupLinkTest {
    private fun ok(text: String?) = (SetupLink.parse(text) as SetupLink.Parsed.Ok).link
    private fun bad(text: String?) = (SetupLink.parse(text) as SetupLink.Parsed.Bad).problem

    @Test fun theReadmeExampleSplitsIntoAddressTokenAndName() {
        val l = ok("https://poi-capture-cloud.4hum-ai.workers.dev/files/#token=pct_abcDEF-_123&name=S%C6%A1n%20%E2%80%93%20phone")
        assertEquals("https://poi-capture-cloud.4hum-ai.workers.dev/files/", l.endpoint)
        assertEquals("pct_abcDEF-_123", l.token)
        assertEquals("Sơn – phone", l.name)
    }

    @Test fun addressIsNormalisedAndNameIsOptional() {
        val l = ok("  http://192.168.1.5:1080/files#name=&token=pct_x  ")
        assertEquals("http://192.168.1.5:1080/files/", l.endpoint)
        assertEquals("pct_x", l.token)
        assertNull(l.name)
        assertNull(ok("https://h.example/files/#token=pct_y").name)
    }

    @Test fun plusInTheNameIsASpaceAndTheFirstRepeatedKeyWins() {
        val l = ok("https://h.example/files/#token=pct_1&token=pct_2&name=A+B&extra=1")
        assertEquals("pct_1", l.token)
        assertEquals("A B", l.name)
    }

    @Test fun notHttpIsRejected() {
        assertEquals(SetupLink.Problem.NOT_HTTP, bad(null))
        assertEquals(SetupLink.Problem.NOT_HTTP, bad(""))
        assertEquals(SetupLink.Problem.NOT_HTTP, bad("hello world"))
        assertEquals(SetupLink.Problem.NOT_HTTP, bad("WIFI:S:office;T:WPA;P:secret;;"))
        assertEquals(SetupLink.Problem.NOT_HTTP, bad("ftp://h.example/files/#token=pct_x"))
        assertEquals(SetupLink.Problem.NOT_HTTP, bad("#token=pct_x"))
    }

    @Test fun noTokenIsRejected() {
        assertEquals(SetupLink.Problem.NO_TOKEN, bad("https://h.example/files/"))
        assertEquals(SetupLink.Problem.NO_TOKEN, bad("https://h.example/files/#name=phone"))
        assertEquals(SetupLink.Problem.NO_TOKEN, bad("https://h.example/files/#token="))
        assertEquals(SetupLink.Problem.NO_TOKEN, bad("https://h.example/files/#token=pct%20x"))
        assertEquals(SetupLink.Problem.NO_TOKEN, bad("https://h.example/files/#token=%ZZ"))
    }

    @Test fun toStringNeverShowsTheToken() {
        assertFalse(ok("https://h.example/files/#token=pct_secret").toString().contains("pct_secret"))
    }

    @Test fun meUrlIsTheOriginPlusMe() {
        assertEquals("https://poi-capture-cloud.4hum-ai.workers.dev/me", CloudMe.url("https://poi-capture-cloud.4hum-ai.workers.dev/files/"))
        assertEquals("http://localhost:1080/me", CloudMe.url("http://localhost:1080/a/b/files"))
        assertNull(CloudMe.url("not a url"))
    }

    @Test fun meParsesOnlyItsSchema() {
        assertEquals(CloudMe("t1", "Sơn – phone", "uploader"),
            CloudMe.parse("""{"schema":"poi-cloud-me/1","id":"t1","name":"Sơn – phone","role":"uploader"}"""))
        assertEquals(CloudMe("admin", "admin", "admin"), CloudMe.parse("""{"schema":"poi-cloud-me/1","id":"admin","role":"admin"}"""))
        assertNull(CloudMe.parse("""{"id":"t1","name":"x","role":"uploader"}"""))
        assertNull(CloudMe.parse("""{"schema":"poi-cloud-me/1","name":"x"}"""))
        assertNull(CloudMe.parse("<html>Not found</html>"))
        assertNull(CloudMe.parse(null))
    }
}

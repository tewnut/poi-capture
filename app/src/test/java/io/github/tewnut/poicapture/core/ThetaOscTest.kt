package io.github.tewnut.poicapture.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The OSC client against a fake Theta (a minimal local HTTP/1.1 server on a ServerSocket: the unit tests compile
 * against android.jar, which has no com.sun.net.httpserver; answering as RICOH THETA API v2.1 documents): info, state,
 * setOptions bodies, takePicture → inProgress → status polling → done with fileUrl, errors, timeouts; and the
 * distance trigger with its busy / offline skips.
 */
class ThetaOscTest {
    private lateinit var base: String
    /** (path, body) of every request */
    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val headers = Collections.synchronizedList(mutableListOf<Map<String, String>>())
    @Volatile private var pollsBeforeDone = 2
    @Volatile private var takeError: String? = null
    @Volatile private var setOptionsRefuse = setOf<String>()
    @Volatile private var apiLevel = "[1,2]"
    @Volatile private var stallMs = 0L
    @Volatile private var neverDone = false
    private val fileNo = AtomicInteger(23)
    private val polls = HashMap<String, Int>()

    private val vn = ZoneId.of("Asia/Ho_Chi_Minh")

    /** A request as the fake camera saw it. */
    private class Ex(val path: String, val headers: Map<String, String>, val body: String, private val out: java.io.OutputStream) {
        fun reply(code: Int, o: JSONObject) {
            val b = o.toString().toByteArray()
            val crlf = "\r\n"
            out.write(("HTTP/1.1 $code X${crlf}Content-Type: application/json;charset=utf-8${crlf}Content-Length: ${b.size}$crlf" +
                "Connection: close$crlf$crlf").toByteArray())
            out.write(b)
            out.flush()
        }
    }

    private lateinit var server: ServerSocket
    private val pool = Executors.newCachedThreadPool()

    @Before fun up() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${server.localPort}"
        Thread {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { serve(sock) }
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun down() { server.close(); pool.shutdownNow() }

    private fun readLine(inp: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0 || c == '\n'.code) break
            if (c != 13) b.write(c)   // CR
        }
        return b.toString("UTF-8")
    }

    private fun serve(sock: Socket) = sock.use {
        val inp = sock.getInputStream().buffered()
        val line = readLine(inp)
        val path = line.split(' ').getOrNull(1) ?: return@use
        val h = HashMap<String, String>()
        while (true) {
            val l = readLine(inp)
            if (l.isEmpty()) break
            h[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        }
        val n = h["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(n).also { var k = 0; while (k < n) { val r = inp.read(it, k, n - k); if (r < 0) break; k += r } }
        runCatching { handle(Ex(path, h, body.toString(Charsets.UTF_8), sock.getOutputStream())) }
    }

    private fun reply(ex: Ex, code: Int, o: JSONObject) = ex.reply(code, o)

    private fun error(name: String?, code: String, msg: String) = JSONObject().put("name", name).put("state", "error")
        .put("error", JSONObject().put("code", code).put("message", msg))

    private fun handle(ex: Ex) {
        val body = ex.body
        val path = ex.path
        requests += path to body
        headers += ex.headers
        if (stallMs > 0) Thread.sleep(stallMs)
        when (path) {
            "/osc/info" -> reply(ex, 200, JSONObject("""{"manufacturer":"RICOH","model":"RICOH THETA X","serialNumber":"12345678",
                "firmwareVersion":"2.10.1","supportUrl":"https://theta360.com/en/support/","gps":true,"gyro":true,"uptime":67,
                "api":["/osc/info","/osc/state","/osc/checkForUpdates","/osc/commands/execute","/osc/commands/status"],
                "endpoints":{"httpPort":80,"httpUpdatesPort":80},"apiLevel":$apiLevel}"""))
            "/osc/state" -> reply(ex, 200, JSONObject("""{"fingerprint":"FIG_0001","state":{"batteryLevel":0.67,
                "storageUri":"http://192.168.1.1/files/abcde/","_captureStatus":"idle","_batteryState":"disconnect"}}"""))
            "/osc/commands/execute" -> {
                val o = JSONObject(body)
                when (o.getString("name")) {
                    "camera.setOptions" -> {
                        val key = o.getJSONObject("parameters").getJSONObject("options").keys().next()
                        if (key in setOptionsRefuse) reply(ex, 400, error("camera.setOptions", "invalidParameterValue", "$key not supported"))
                        else reply(ex, 200, JSONObject().put("name", "camera.setOptions").put("state", "done"))
                    }
                    "camera.takePicture" -> {
                        val e = takeError
                        if (e != null) { reply(ex, 400, error("camera.takePicture", e, "camera busy")); return }
                        val id = "${fileNo.get()}"
                        synchronized(polls) { polls[id] = 0 }
                        reply(ex, 200, JSONObject().put("name", "camera.takePicture").put("state", "inProgress").put("id", id)
                            .put("progress", JSONObject().put("completion", 0)))
                    }
                    else -> reply(ex, 400, error(o.getString("name"), "unknownCommand", "?"))
                }
            }
            "/osc/commands/status" -> {
                val id = JSONObject(body).getString("id")
                val n = synchronized(polls) { (polls[id] ?: 0).also { polls[id] = it + 1 } }
                if (neverDone || n < pollsBeforeDone) reply(ex, 200, JSONObject().put("name", "camera.takePicture").put("state", "inProgress")
                    .put("id", id).put("progress", JSONObject().put("completion", 0.5)))
                else reply(ex, 200, JSONObject().put("name", "camera.takePicture").put("state", "done").put("results",
                    JSONObject().put("fileUrl", "http://192.168.1.1/files/abcde/100RICOH/R00100${fileNo.getAndIncrement()}.JPG")))
            }
            else -> reply(ex, 404, JSONObject())
        }
    }

    private fun client(timeoutMs: Int = 2000) = OscClient(HttpOscTransport(base), timeoutMs)
    private fun executes() = requests.filter { it.first == "/osc/commands/execute" }.map { JSONObject(it.second) }

    @Test fun infoAndState() {
        val c = client()
        val i = c.info()
        assertEquals("RICOH THETA X", i.model)
        assertEquals("12345678", i.serial)
        assertEquals("2.10.1", i.firmware)
        assertEquals(listOf(1, 2), i.apiLevels)
        val j = i.toJson()
        assertEquals(setOf("model", "serial", "firmware"), j.keys().asSequence().toSet())
        assertEquals("RICOH THETA X", j.getString("model")); assertEquals("12345678", j.getString("serial"))
        val s = c.state()
        assertEquals(0.67, s.batteryLevel!!, 1e-9)
        assertEquals("idle", s.captureStatus)
        // a POST to /osc/state, the OSC headers on every request
        assertTrue(headers.all { it["x-xsrf-protected"] == "1" && it["accept"] == "application/json" })
    }

    @Test fun gpsInfoBodyIsWhatTheSpecWants() {
        client().setOptions(JSONObject().put("gpsInfo", ThetaOsc.gpsInfo(10.7769421234, 106.7009245678, 12.345,
            1795000000000L, vn)))
        val body = JSONObject(requests.single().second)
        assertEquals("camera.setOptions", body.getString("name"))
        assertEquals(setOf("name", "parameters"), body.keys().asSequence().toSet())
        val g = body.getJSONObject("parameters").getJSONObject("options").getJSONObject("gpsInfo")
        assertEquals(setOf("lat", "lng", "_altitude", "_dateTimeZone", "_datum"), g.keys().asSequence().toSet())
        assertEquals(10.776942, g.getDouble("lat"), 0.0)
        assertEquals(106.700925, g.getDouble("lng"), 0.0)
        assertEquals(12.35, g.getDouble("_altitude"), 1e-9)
        assertEquals("2026:11:18 18:06:40+07:00", g.getString("_dateTimeZone"))   // 1795000000000 = 2026-11-18T11:06:40Z
        assertEquals("WGS84", g.getString("_datum"))
        assertEquals("application/json;charset=utf-8", headers.single()["content-type"])
    }

    @Test fun dateTimeZoneFormat() {
        assertEquals("2026:11:18 18:06:40+07:00", ThetaOsc.dateTimeZone(1795000000000L, vn))
        assertEquals("2026:11:18 11:06:40+00:00", ThetaOsc.dateTimeZone(1795000000999L, ZoneId.of("UTC")))
    }

    @Test fun connectSetsTheClockAndNotesRefusedOptions() {
        setOptionsRefuse = setOf("offDelay")
        val r = client().connect(1795000000000L, vn)
        assertEquals("RICOH THETA X", r.info.model)
        assertTrue(r.clockSet)
        assertEquals(0.67, r.batteryLevel!!, 1e-9)
        assertEquals(listOf("offDelay: invalidParameterValue: offDelay not supported"), r.notes)
        val opts = executes().map { it.getJSONObject("parameters").getJSONObject("options") }
        assertEquals("2026:11:18 18:06:40+07:00", opts.first().getString("dateTimeZone"))
        // a Theta X also gets its GPS tagging turned on (it ignores gpsInfo without it)
        assertEquals(listOf("dateTimeZone", "captureMode", "exposureDelay", "sleepDelay", "offDelay", "_gpsTagRecording"), opts.map { it.keys().next() })
        assertEquals("image", opts[1].getString("captureMode"))
    }

    @Test fun apiV20CamerasAreRefused() {
        apiLevel = "[1]"
        try { client().connect(0, vn); fail() } catch (e: OscException) { assertEquals("api-level", e.code) }
    }

    @Test fun takePicturePollsUntilDone() {
        pollsBeforeDone = 3
        val slept = mutableListOf<Long>()
        val (id, url) = client().takePictureAndWait(pollMs = 250, sleep = { slept += it })
        assertEquals("23", id)
        assertEquals("http://192.168.1.1/files/abcde/100RICOH/R0010023.JPG", url)
        assertEquals("R0010023.JPG", ThetaOsc.fileName(url))
        assertEquals(listOf(250L, 250L, 250L, 250L), slept)
        val statuses = requests.filter { it.first == "/osc/commands/status" }
        assertEquals(4, statuses.size)
        assertEquals("""{"id":"23"}""", statuses.first().second)
        assertEquals("""{"name":"camera.takePicture"}""", executes().single().toString())
    }

    @Test fun errorsAndTimeouts() {
        takeError = "disabledCommand"
        try { client().takePicture(); fail() } catch (e: OscException) {
            assertEquals("disabledCommand", e.code); assertEquals("disabledCommand: camera busy", e.short())
        }
        takeError = null
        // never done: gives up after maxMs (a fake clock)
        neverDone = true
        var t = 0L
        try { client().takePictureAndWait(pollMs = 300, maxMs = 1000, sleep = { t += it }, clock = { t }); fail() }
        catch (e: OscException) { assertEquals("timeout", e.code) }
        neverDone = false
        // no answer within the socket timeout
        stallMs = 800
        try { client(timeoutMs = 200).info(); fail() } catch (e: OscException) { assertEquals("timeout", e.code) }
        stallMs = 0
        // nothing listening (the camera's Wi-Fi gone)
        val dead = OscClient(HttpOscTransport("http://127.0.0.1:1"), 500)
        try { dead.info(); fail() } catch (e: OscException) { assertTrue(e.code == "io" || e.code == "timeout") }
    }

    // ---------- the distance trigger ----------

    private fun fixAt(tS: Int, northM: Double, speed: Double? = null) =
        ShotFix(tS * 1000L, 10.78 + northM / 111_195.0, 106.70, 4.0, 5.0, speed, 0.0)

    @Test fun everyEightMetresNoneWhileStandingAndBusySkips() {
        val shots = Collections.synchronizedList(mutableListOf<ThetaShot>())
        val queued = mutableListOf<Runnable>()
        val exec = Executor { queued += it }   // shots run only when the test says so: the camera stays "busy"
        val s = ThetaShooter({ client() }, 8.0, exec, onShot = { shots += it }, zone = vn, pollMs = 1, sleep = {})
        pollsBeforeDone = 0
        assertFalse(s.onFix(fixAt(0, 0.0)))                // first fix: nothing to measure yet
        assertTrue(s.onFix(fixAt(1, 5.0)))                 // first moving fix: shoots
        assertFalse(s.onFix(fixAt(2, 10.0)))               // 5 m since: not yet
        assertFalse(s.onFix(fixAt(3, 15.0)))               // 10 m: due, but the camera is still busy → skipped
        assertEquals(1, s.stats.skippedBusy)
        queued.removeAt(0).run()                           // the first shot completes
        assertEquals(1, shots.size)
        assertFalse(s.onFix(fixAt(4, 15.2, speed = 0.1)))  // standing (Doppler speed): no distance
        assertFalse(s.onFix(fixAt(30, 15.3, speed = 0.0)))
        assertFalse(s.onFix(fixAt(31, 20.0)))              // 4.7 m after the skip
        assertTrue(s.onFix(fixAt(32, 24.0)))               // 8.7 m
        queued.removeAt(0).run()
        assertEquals(2, shots.size)
        assertEquals(2, s.stats.shots)
        val first = shots[0]
        assertTrue(first.ok)
        assertEquals("R0010023.JPG", first.fileName)
        assertEquals("23", first.cmdId)
        val j = first.toJson()
        assertEquals(setOf("tMs", "lat", "lon", "accuracyM", "bearing", "speedMps", "cmdId", "fileUrl", "fileName", "ok"), j.keys().asSequence().toSet())
        // gpsInfo was set to the fix before each takePicture
        val names = executes().map { o -> o.getString("name") + (o.optJSONObject("parameters")?.getJSONObject("options")?.keys()?.next()?.let { ":$it" } ?: "") }
        assertEquals(listOf("camera.setOptions:gpsInfo", "camera.takePicture", "camera.setOptions:gpsInfo", "camera.takePicture"), names)
        val g = executes()[2].getJSONObject("parameters").getJSONObject("options").getJSONObject("gpsInfo")
        assertEquals(10.78 + 24.0 / 111_195.0, g.getDouble("lat"), 1e-6)
    }

    @Test fun offlineAndFailedShotsAreCounted() {
        val shots = mutableListOf<ThetaShot>()
        var connected = false
        val s = ThetaShooter({ if (connected) client() else null }, 8.0, Executor { it.run() }, onShot = { shots += it },
            zone = vn, pollMs = 1, sleep = {})
        s.onFix(fixAt(0, 0.0))
        assertFalse(s.onFix(fixAt(1, 9.0)))
        assertEquals(1, s.stats.skippedOffline)
        connected = true
        takeError = "disabledCommand"
        assertTrue(s.onFix(fixAt(2, 18.0)))
        assertEquals(1, s.stats.failed)
        assertEquals("disabledCommand: camera busy", s.stats.lastError)
        assertFalse(shots.single().ok)
        assertEquals("disabledCommand: camera busy", shots.single().toJson().getString("error"))
        assertTrue(shots.single().toJson().isNull("fileName"))
        assertFalse(s.stats.busy)
        // a refused gpsInfo still takes the photo, noted on the shot
        takeError = null
        setOptionsRefuse = setOf("gpsInfo")
        pollsBeforeDone = 0
        assertTrue(s.onFix(fixAt(3, 27.0)))
        assertTrue(shots.last().ok)
        assertEquals("gpsInfo: invalidParameterValue: gpsInfo not supported", shots.last().error)
    }

    @Test fun realThreadOneShotAtATime() {
        val pool = Executors.newSingleThreadExecutor()
        val shots = Collections.synchronizedList(mutableListOf<ThetaShot>())
        pollsBeforeDone = 1
        stallMs = 50     // each request ~50 ms: a shot takes ~200 ms
        val s = ThetaShooter({ client() }, 8.0, pool, onShot = { shots += it }, zone = vn, pollMs = 10)
        s.onFix(fixAt(0, 0.0))
        var started = 0
        for (k in 1..20) if (s.onFix(fixAt(k, k * 9.0))) started++   // a trigger every fix, faster than the camera
        pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(started, shots.size)
        assertEquals(20, started + s.stats.skippedBusy)
        assertTrue(s.stats.skippedBusy > 0)
        assertEquals(shots.map { it.fileName }.distinct().size, shots.size)
    }

    @Test fun rideJson() {
        var c = ThetaRide.start(8.0)
        assertTrue(ThetaRide.isTheta(c))
        assertEquals(8.0, ThetaRide.everyM(c), 0.0)
        assertEquals(0, c.getJSONArray("syncs").length())
        c = ThetaRide.withCamera(c, OscInfo("RICOH", "RICOH THETA Z1", "10010104", "3.01.1", listOf(2)))
        c = ThetaRide.withShot(c, ThetaShot(1L, 10.0, 106.0, 4.0, 90.0, 6.0, "5", "http://192.168.1.1/files/x/100RICOH/R0010005.JPG", true))
        c = ThetaRide.withShot(c, ThetaShot(2L, 10.0, 106.0, 4.0, 90.0, 6.0, null, null, false, "timeout"))
        assertEquals(1 to 1, ThetaRide.counts(JSONObject(c.toString())))
        assertEquals("RICOH THETA Z1", c.getJSONObject("camera").getString("model"))
        assertEquals("theta-osc", c.getString("control"))
        assertNull(ThetaRide.control(Camera360.empty()))
        assertEquals(ThetaOsc.DEFAULT_EVERY_M, ThetaRide.everyM(Camera360.empty()), 0.0)
        assertEquals("12345678", ThetaOsc.passwordFrom("THETAYR12345678.OSC"))
    }
}

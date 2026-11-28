package io.github.tewnut.poicapture.core

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera check of Settings → Camera 360 ("Kiểm tra camera"): for a Ricoh Theta a run of steps against the camera
 * itself ([ThetaCheckRunner]); for the cameras that record on their own a tick list made each day ([ManualItem]).
 * Pure JVM: the Wi-Fi link, the phone's GPS and the EXIF reader are passed in (Android ones in capture/ThetaCheckAndroid.kt).
 */
enum class CheckStep { WIFI, CAMERA, CLOCK, GPS, SHOT, THUMB, EXIF_GPS, DELETE }

/** WARN: not right, but the ride still works (shown ⚠, not counted as failed). */
enum class CheckOutcome { PENDING, RUNNING, OK, WARN, FAIL, SKIPPED }

/** Why a step failed / warned: each has its one-line fix on the screen. */
enum class CheckFix { SERIAL_MISSING, WIFI, NOT_RICOH, WRONG_SERIAL, NO_ANSWER, CLOCK, NO_GPS, GPS_REFUSED, SHOT, DOWNLOAD,
    EXIF_NO_GPS, EXIF_FAR, DELETE, ERROR }

data class CheckResult(
    val step: CheckStep,
    val outcome: CheckOutcome = CheckOutcome.PENDING,
    val fix: CheckFix? = null,
    /** The camera's / the system's own words, when there are some. */
    val error: String? = null,
    val model: String? = null, val serial: String? = null, val batteryPct: Int? = null,
    val accuracyM: Double? = null,
    val shotSeconds: Double? = null,
    /** EXIF_GPS: how far the photo's EXIF position is from the fix sent. */
    val distanceM: Double? = null,
)

class CheckState(val results: List<CheckResult>, val running: Boolean, val thumb: ByteArray?) {
    val passed: Boolean get() = Camera360Check.passed(results)
    fun of(step: CheckStep): CheckResult = results.first { it.step == step }
}

/** A phone GPS fix sent to the camera. */
data class PhoneFix(val lat: Double, val lon: Double, val altitudeM: Double?, val accuracyM: Double?)

/** What the photo's EXIF says: its GPS position (null = none) and its embedded thumbnail. */
class ExifGps(val lat: Double?, val lon: Double?, val thumbnail: ByteArray?)

data class LinkOutcome(val ok: Boolean, val fix: CheckFix? = null, val error: String? = null)

/** The Wi-Fi link to the camera, as the check needs it ([io.github.tewnut.poicapture.capture.ThetaLink] on the phone). */
interface CheckLink {
    /** Connected within [timeoutMs], asking for the camera's Wi-Fi when not. Gives up when [cancelled]. */
    fun connect(timeoutMs: Long, cancelled: () -> Boolean): LinkOutcome
    fun client(): OscClient?
    fun files(): OscFiles?
    /** The connect handshake set the camera's clock (dateTimeZone). */
    val clockSet: Boolean
}

/** A recent phone fix (≤ 2 min old, else one asked for, at most [timeoutMs]); null = none. */
fun interface PhoneGps { fun fix(timeoutMs: Long): PhoneFix? }

fun interface ExifGpsReader { fun read(jpegStart: ByteArray): ExifGps? }

/** What the 360 cameras that record on their own need before a ride, ticked by hand. [id]: kept in the preferences. */
enum class ManualItem(val id: String) {
    LABS("labs"), SETUP_QR("setup_qr"), CAMERA_GPS("gps"), CLOCK("clock"), GPS_IF_ANY("gps_any"), CARD("card"), BATTERY("battery")
}

object Camera360Check {
    /** The steps that must all be ✓ for "checked today" (Wi-Fi … the photo downloaded). */
    val PASS_STEPS = CheckStep.entries.filter { it <= CheckStep.THUMB }
    /** EXIF position farther than this from the fix sent: ⚠. */
    const val MAX_EXIF_M = 100.0
    /** A phone fix less precise than this is not used (the 100 m comparison would mean nothing). */
    const val MAX_FIX_ACCURACY_M = 50.0
    /** A last known fix older than this is not used. */
    const val MAX_FIX_AGE_MS = 120_000L

    fun passed(results: List<CheckResult>): Boolean =
        PASS_STEPS.all { s -> results.any { it.step == s && it.outcome == CheckOutcome.OK } }

    /** The fastest speed at which a photo every [everyM] metres can be kept up with when one takes [shotSeconds]. */
    fun maxKmh(everyM: Double, shotSeconds: Double): Double? =
        if (shotSeconds <= 0 || everyM <= 0) null else everyM / shotSeconds * 3.6

    /** The EXIF position against the fix sent: ✓ within [MAX_EXIF_M], ⚠ farther or missing. */
    fun exifResult(sent: PhoneFix, exif: ExifGps?): CheckResult {
        val lat = exif?.lat; val lon = exif?.lon
        if (lat == null || lon == null || (lat == 0.0 && lon == 0.0))
            return CheckResult(CheckStep.EXIF_GPS, CheckOutcome.WARN, CheckFix.EXIF_NO_GPS)
        val d = ThetaOsc.distanceM(sent.lat, sent.lon, lat, lon)
        return if (d <= MAX_EXIF_M) CheckResult(CheckStep.EXIF_GPS, CheckOutcome.OK, distanceM = d)
        else CheckResult(CheckStep.EXIF_GPS, CheckOutcome.WARN, CheckFix.EXIF_FAR, distanceM = d)
    }

    /** The serial the camera reports against the one typed (digits; one may carry a few more): as ThetaLink's handshake. */
    fun serialMatches(asked: String, reported: String?): Boolean {
        val a = ThetaOsc.passwordFrom(asked)
        if (a.isEmpty()) return true
        val r = reported?.filter { it.isDigit() } ?: return false
        return r.isNotEmpty() && (r == a || r.endsWith(a) || a.endsWith(r))
    }

    /** [savedDate] ("2026-11-28", "" = never) is [today]. */
    fun checkedToday(savedDate: String?, today: LocalDate): Boolean = savedDate == today.toString()

    /** The ticks kept, if they were made [today]; a new day starts unticked. */
    fun ticksToday(savedDate: String?, ticks: Set<String>, today: LocalDate): Set<String> =
        if (checkedToday(savedDate, today)) ticks else emptySet()

    fun manualItems(model: Camera360Model): List<ManualItem> = when (model) {
        Camera360Model.GOPRO -> listOf(ManualItem.LABS, ManualItem.SETUP_QR, ManualItem.CAMERA_GPS, ManualItem.CARD, ManualItem.BATTERY)
        Camera360Model.INSTA360, Camera360Model.OTHER -> listOf(ManualItem.CLOCK, ManualItem.GPS_IF_ANY, ManualItem.CARD, ManualItem.BATTERY)
        Camera360Model.NONE, Camera360Model.THETA -> emptyList()
    }

    fun manualDone(model: Camera360Model, ticks: Set<String>): Boolean =
        manualItems(model).let { items -> items.isNotEmpty() && items.all { it.id in ticks } }

    /** A Theta X: it writes positions only with its own `_gpsTagRecording` "on" (RICOH THETA API v2.1, gpsInfo note *2). */
    fun isThetaX(model: String?): Boolean = model?.trim()?.matches(Regex("(?i)RICOH\\s+THETA\\s+X")) == true

    fun isJpeg(b: ByteArray?): Boolean = b != null && b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
}

/**
 * One run of the Theta check (docs/camera360.md §4), step by step on the calling (background) thread, each change
 * reported to [run]'s callback:
 * 1 the camera's Wi-Fi, 2 the right RICOH (model, serial, battery), 3 its clock, 4 gpsInfo with a phone fix,
 * 5 a test photo (timed), 6 its thumbnail (`?type=thumb`; else the file's first bytes), 7 the photo's EXIF position
 * against the fix, 8 the test photo deleted (`camera.delete`), so it never mixes into a ride's import.
 * 1–2 failing stops the run (the rest –); the later steps go on where they can. [cancel] stops between steps; a test
 * photo already taken is still deleted.
 */
class ThetaCheckRunner(
    private val link: CheckLink,
    private val gps: PhoneGps,
    private val exif: ExifGpsReader,
    private val askedSerial: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val connectTimeoutMs: Long = 65_000,
    private val gpsTimeoutMs: Long = 20_000,
    private val shotMaxMs: Long = 30_000,
    private val pollMs: Long = 300,
    private val headBytes: Int = 256 * 1024,
    private val thumbMaxBytes: Int = 1024 * 1024,
    private val fileTimeoutMs: Int = 10_000,
) {
    val cancel = AtomicBoolean(false)

    private val res = CheckStep.entries.map { CheckResult(it) }.toMutableList()
    private var thumb: ByteArray? = null
    private var onUpdate: (CheckState) -> Unit = {}

    private fun emit(running: Boolean = true) = onUpdate(CheckState(res.toList(), running, thumb))
    private fun start(s: CheckStep) { res[s.ordinal] = CheckResult(s, CheckOutcome.RUNNING); emit() }
    private fun done(r: CheckResult) { res[r.step.ordinal] = r; emit() }
    private fun cancelled() = cancel.get()

    fun run(onUpdate: (CheckState) -> Unit = {}): CheckState {
        this.onUpdate = onUpdate
        emit()
        var fileUrl: String? = null
        var client: OscClient? = null
        try {
            steps { c, url -> client = c; fileUrl = url }
        } catch (e: Exception) {
            // anything unexpected: the step running fails with it, the others are left as they are
            res.indexOfFirst { it.outcome == CheckOutcome.RUNNING }.takeIf { it >= 0 }?.let {
                res[it] = CheckResult(res[it].step, CheckOutcome.FAIL, CheckFix.ERROR, e.message ?: e.javaClass.simpleName)
            }
        }
        val c = client; val url = fileUrl
        if (c != null && url != null) {
            start(CheckStep.DELETE)
            done(try { c.delete(listOf(url)); CheckResult(CheckStep.DELETE, CheckOutcome.OK) }
                 catch (e: Exception) { CheckResult(CheckStep.DELETE, CheckOutcome.WARN, CheckFix.DELETE, short(e)) })
        }
        for (i in res.indices) if (res[i].outcome == CheckOutcome.PENDING || res[i].outcome == CheckOutcome.RUNNING)
            res[i] = CheckResult(res[i].step, CheckOutcome.SKIPPED)
        val end = CheckState(res.toList(), false, thumb)
        onUpdate(end)
        return end
    }

    private fun short(e: Exception) = (e as? OscException)?.short() ?: e.message ?: e.javaClass.simpleName

    /** Steps 1–7; [taken]: the client and the test photo's URL as soon as there is one (step 8 deletes it). */
    private fun steps(taken: (OscClient, String?) -> Unit) {
        // 1 Wi-Fi
        if (cancelled()) return
        start(CheckStep.WIFI)
        val l = link.connect(connectTimeoutMs, ::cancelled)
        if (cancelled()) return
        if (!l.ok) { done(CheckResult(CheckStep.WIFI, CheckOutcome.FAIL, l.fix ?: CheckFix.WIFI, l.error)); return }
        done(CheckResult(CheckStep.WIFI, CheckOutcome.OK))

        // 2 the right camera
        start(CheckStep.CAMERA)
        val c = link.client()
        if (c == null) { done(CheckResult(CheckStep.CAMERA, CheckOutcome.FAIL, CheckFix.NO_ANSWER)); return }
        taken(c, null)
        val info = try { c.info() } catch (e: OscException) {
            done(CheckResult(CheckStep.CAMERA, CheckOutcome.FAIL, CheckFix.NO_ANSWER, e.short())); return
        }
        val battery = try { c.state().batteryLevel } catch (e: OscException) { null }
        val ricoh = info.manufacturer?.contains("RICOH", ignoreCase = true) == true
        val serialOk = Camera360Check.serialMatches(askedSerial, info.serial)
        val fix = when { !ricoh -> CheckFix.NOT_RICOH; !serialOk -> CheckFix.WRONG_SERIAL; else -> null }
        done(CheckResult(CheckStep.CAMERA, if (fix == null) CheckOutcome.OK else CheckOutcome.FAIL, fix,
            model = info.model, serial = info.serial, batteryPct = battery?.let { (it * 100).toInt() }))
        if (fix != null) return

        // 3 the camera's clock: set by the connect handshake, else once more here
        if (cancelled()) return
        start(CheckStep.CLOCK)
        done(if (link.clockSet) CheckResult(CheckStep.CLOCK, CheckOutcome.OK) else try {
            c.setOptions(JSONObject().put("dateTimeZone", ThetaOsc.dateTimeZone(now(), zone)))
            CheckResult(CheckStep.CLOCK, CheckOutcome.OK)
        } catch (e: OscException) { CheckResult(CheckStep.CLOCK, CheckOutcome.FAIL, CheckFix.CLOCK, e.short()) })

        // 4 the phone's position into the camera
        if (cancelled()) return
        start(CheckStep.GPS)
        val phone = gps.fix(gpsTimeoutMs)
        var sent: PhoneFix? = null
        if (phone == null) done(CheckResult(CheckStep.GPS, CheckOutcome.FAIL, CheckFix.NO_GPS))
        else {
            // Theta X: positions are written only with its own switch on (best effort: other models refuse the name)
            if (Camera360Check.isThetaX(info.model))
                try { c.setOptions(JSONObject().put("_gpsTagRecording", "on")) } catch (_: OscException) {}
            done(try {
                c.setOptions(JSONObject().put("gpsInfo", ThetaOsc.gpsInfo(phone.lat, phone.lon, phone.altitudeM, now(), zone)))
                sent = phone
                CheckResult(CheckStep.GPS, CheckOutcome.OK, accuracyM = phone.accuracyM)
            } catch (e: OscException) { CheckResult(CheckStep.GPS, CheckOutcome.FAIL, CheckFix.GPS_REFUSED, e.short()) })
        }

        // 5 a test photo, timed
        if (cancelled()) return
        start(CheckStep.SHOT)
        val t0 = now()
        val url = try { c.takePictureAndWait(pollMs, shotMaxMs, sleep, now).second } catch (e: OscException) {
            done(CheckResult(CheckStep.SHOT, CheckOutcome.FAIL, CheckFix.SHOT, e.short())); return
        }
        if (url == null) { done(CheckResult(CheckStep.SHOT, CheckOutcome.FAIL, CheckFix.SHOT, "no fileUrl")); return }
        taken(c, url)
        done(CheckResult(CheckStep.SHOT, CheckOutcome.OK, shotSeconds = (now() - t0) / 1000.0))

        // 6 the photo to the phone: its thumbnail, else the start of the file (its EXIF thumbnail, if any)
        if (cancelled()) return
        start(CheckStep.THUMB)
        val files = link.files()
        var head: ByteArray? = null
        if (files == null) { done(CheckResult(CheckStep.THUMB, CheckOutcome.FAIL, CheckFix.DOWNLOAD, "not connected")); return }
        var thumbError: String? = null
        val t = try { files.get("$url?type=thumb", thumbMaxBytes, false, fileTimeoutMs) } catch (e: Exception) { thumbError = short(e); null }
        if (t != null && t.size >= thumbMaxBytes && Camera360Check.isJpeg(t)) {
            head = t.copyOf(minOf(t.size, headBytes))   // type=thumb ignored: the whole photo came; its start serves step 7
        } else if (Camera360Check.isJpeg(t)) thumb = t
        if (thumb == null && head == null) head = try { files.get(url, headBytes, true, fileTimeoutMs) } catch (e: Exception) {
            thumbError = short(e); null
        }
        if (thumb == null && head != null) thumb = exif.read(head)?.thumbnail?.takeIf { Camera360Check.isJpeg(it) }
        done(if (thumb != null || head?.isNotEmpty() == true) CheckResult(CheckStep.THUMB, CheckOutcome.OK)
             else CheckResult(CheckStep.THUMB, CheckOutcome.FAIL, CheckFix.DOWNLOAD, thumbError))

        // 7 the position in the photo's EXIF against the one sent (first bytes of the full photo only)
        if (cancelled() || sent == null) return
        start(CheckStep.EXIF_GPS)
        if (head == null) head = try { files.get(url, headBytes, true, fileTimeoutMs) } catch (e: Exception) {
            done(CheckResult(CheckStep.EXIF_GPS, CheckOutcome.FAIL, CheckFix.DOWNLOAD, short(e))); return
        }
        done(Camera360Check.exifResult(sent!!, exif.read(head!!)))
    }
}

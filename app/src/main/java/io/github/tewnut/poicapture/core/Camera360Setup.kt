package io.github.tewnut.poicapture.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The 360 camera the contributor uses (Settings → Camera 360), kept as [json] in the preferences. */
enum class Camera360Model(val json: String) {
    NONE("none"), GOPRO("gopro"), INSTA360("insta360"), THETA("theta"), OTHER("other");

    companion object {
        /**
         * The stored choice; nothing stored yet (app <= 0.22): a Ricoh Theta when the old "control a Theta" switch
         * was on, else none.
         */
        fun of(stored: String?, legacyThetaOn: Boolean): Camera360Model =
            entries.firstOrNull { it.json == stored } ?: if (legacyThetaOn) THETA else NONE
    }
}

/**
 * A Ricoh Theta's serial read off the sticker under the camera (Settings → Camera 360 → Đọc số serial), from the text
 * ML Kit found on a camera frame: "SN YR12345678", "S/N: 10012345", "No. 10012345", or the Wi-Fi name
 * "THETAYR12345678.OSC". A serial is an optional 2-letter prefix and 8 digits; plain 8 digits count only right after
 * SN / S/N / No / Serial (a lone 8-digit number on a label is too often something else).
 */
object ThetaSerial {
    private const val KEY = "(?<![A-Za-z])(?:S\\s*/\\s*N|SN|SERIAL(?:\\s*NO)?|NO)\\s*[.:#]?\\s*"
    private val afterKey = Regex(KEY + "([A-Za-z]{2})?\\s?(\\d{8})(?!\\d)", RegexOption.IGNORE_CASE)
    private val ssid = Regex("THETA([A-Z]{2})(\\d{8})(?!\\d)", RegexOption.IGNORE_CASE)
    private val prefixed = Regex("(?<![A-Za-z0-9])([A-Z]{2})(\\d{8})(?!\\d)")

    /** The serial in [text] (prefix upper-cased, e.g. "YR12345678" or "10012345"), null if none is there. */
    fun find(text: String): String? {
        for (re in listOf(afterKey, ssid, prefixed)) {
            val m = re.find(text) ?: continue
            return (m.groupValues[1] + m.groupValues[2]).uppercase()
        }
        return null
    }
}

/**
 * The GoPro Labs setup QR for a GoPro MAX with Labs firmware (Settings → Camera 360 → GoPro MAX): the phone's date and
 * time to the millisecond with its time zone, GPS on, 360 video 5.6K 30 fps. Regenerated while shown (the time in it
 * goes stale), like GoPro's own precision-time page.
 *
 * Only commands stated in GoPro's documentation (read 2026-11-28):
 *  - https://gopro.github.io/labs/control/tech/ "Time and date":
 *      "oTyymmddhhmmss.sss for precision date and time e.g. oT241208174033.556 for 2024 December 8, at 5:40:33.556pm"
 *      "oTD1 for daylight savings on" / "oTD0 for daylight savings off"
 *      "oTZ-7 or oTZ-420 e.g. -7 hour or -420 minutes timezone offset"
 *  - https://gopro.github.io/labs/control/precisiontime/ (its page script, the sign and the DST split): it builds
 *      cmd = "oT" + yy + mm + dd + h + m + s + "." + ms + "oTD" + td + "oTZ" + tz + "oTI" + id
 *    with the local wall-clock time, tz = the zone's STANDARD offset east of UTC (getTimezoneOffset() with the DST hour
 *    taken out, negated), in hours when whole, else minutes, and td = 1 while daylight saving is on. Vietnam: oTZ7.
 *    "oTI<id>" (a random id of the page) is not in the command reference: left out.
 *  - tech page "GPS Active": "g1 or g: GPS on".
 *  - tech page "Mode commands": "mV - mode Video"; "GoPro MAX/360 Shooting Modes": "m3 - mode 360°";
 *    "Resolutions": "r5 - 5.3k for HERO and 5.6K 360° for GoPro MAX"; frame rates: "p30 - 30 fps".
 *    Concatenation (tech page): "mVr4p60x-.5cFw55$BITR=150$LEVL=6 ... No space or other delimiters are used" and
 *    "The individual commands within a QR code are performed in order". The tech page also says "Note: MAX has around
 *    H11 Labs capabilities" and "Not all modes are available on every camera".
 *    Each piece is documented; the whole "mVm3r5p30" on an original MAX is not shown as an example anywhere: to be
 *    checked on a camera.
 *  - Redrawn every [REDRAW_MS] rather than once a second: the time inside is to the millisecond and GoPro's own page
 *    redraws it every animation frame (requestAnimationFrame), so a code a second old would set the clock up to 1 s late.
 */
object GoProLabs {
    /** GoPro Labs: what it is, firmware downloads per camera (GoPro MAX listed under "older cameras"). */
    const val LABS_URL = "https://gopro.github.io/labs/"
    /** Redraw period while shown: the time inside is to the millisecond (GoPro's page redraws every frame). */
    const val REDRAW_MS = 100L
    /** GPS on (tech page, "GPS Active"). */
    const val GPS_ON = "g1"
    /** Video, 360°, 5.6K (r5 on MAX), 30 fps (tech page: mode, MAX/360 modes, resolutions, frame rates). */
    const val MAX_360_5K6_30 = "mVm3r5p30"

    private val stamp = DateTimeFormatter.ofPattern("yyMMddHHmmss.SSS")

    /** "oT261128101502.123oTD0oTZ7": the date and time commands for [local] wall-clock time. */
    fun timeCommand(local: LocalDateTime, standardOffsetMinutes: Int, dst: Boolean): String {
        val tz = if (standardOffsetMinutes % 60 == 0) standardOffsetMinutes / 60 else standardOffsetMinutes
        return "oT" + stamp.format(local) + "oTD" + (if (dst) 1 else 0) + "oTZ" + tz
    }

    /** The whole setup QR text at [now] in [zone]: time, GPS on, 360 video 5.6K 30. */
    fun setupCommand(now: Instant, zone: ZoneId): String {
        val rules = zone.rules
        val local = LocalDateTime.ofInstant(now, zone)
        return timeCommand(local, rules.getStandardOffset(now).totalSeconds / 60, rules.isDaylightSavings(now)) +
            GPS_ON + MAX_360_5K6_30
    }
}

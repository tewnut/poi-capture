package io.github.tewnut.poicapture.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import io.github.tewnut.poicapture.core.Camera360Check
import io.github.tewnut.poicapture.core.CheckFix
import io.github.tewnut.poicapture.core.CheckLink
import io.github.tewnut.poicapture.core.ExifGps
import io.github.tewnut.poicapture.core.ExifGpsReader
import io.github.tewnut.poicapture.core.LinkOutcome
import io.github.tewnut.poicapture.core.OscClient
import io.github.tewnut.poicapture.core.OscFiles
import io.github.tewnut.poicapture.core.PhoneFix
import io.github.tewnut.poicapture.core.PhoneGps
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The camera check's link: [ThetaLink], connected with the saved serial / password when it is not. */
class ThetaCheckLink(private val ctx: Context, private val serial: String, private val password: String) : CheckLink {
    override fun connect(timeoutMs: Long, cancelled: () -> Boolean): LinkOutcome {
        if (serial.isBlank()) return LinkOutcome(false, CheckFix.SERIAL_MISSING)
        val s0 = ThetaLink.state.value.status
        if (s0 == ThetaLink.Status.CONNECTED) return LinkOutcome(true)
        if (s0 != ThetaLink.Status.CONNECTING) {
            // ThetaLink is driven from the main thread (as the Connect button does)
            val asked = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { ThetaLink.connect(ctx, serial, password); asked.countDown() }
            asked.await(5, TimeUnit.SECONDS)
        }
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (cancelled()) return LinkOutcome(false, null, "cancelled")
            val st = ThetaLink.state.value
            when (st.status) {
                ThetaLink.Status.CONNECTED -> return LinkOutcome(true)
                ThetaLink.Status.FAILED -> return LinkOutcome(false, when {
                    st.error == ThetaLink.ERR_WIFI -> CheckFix.WIFI
                    st.error == "serial?" -> CheckFix.SERIAL_MISSING
                    st.error?.startsWith("not this camera") == true -> CheckFix.WRONG_SERIAL
                    else -> CheckFix.NO_ANSWER
                }, st.error?.takeIf { it != ThetaLink.ERR_WIFI })
                else -> Thread.sleep(250)
            }
        }
        return LinkOutcome(false, CheckFix.WIFI, "timeout")
    }

    override fun client(): OscClient? = ThetaLink.client()
    override fun files(): OscFiles? = ThetaLink.files()
    override val clockSet: Boolean get() = ThetaLink.state.value.clockSet
}

/**
 * A phone fix for the check from the platform LocationManager: the freshest last known GPS / fused / network fix
 * ≤ 2 min old and ≤ 50 m precise, else one GPS fix asked for (at most the timeout). No permission / nothing: null.
 */
class PlatformPhoneGps(private val ctx: Context) : PhoneGps {
    override fun fix(timeoutMs: Long): PhoneFix? {
        val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (android.os.Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        val nowNs = SystemClock.elapsedRealtimeNanos()
        try {
            providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .filter { (nowNs - it.elapsedRealtimeNanos) / 1_000_000 <= Camera360Check.MAX_FIX_AGE_MS && usable(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { return it.toFix() }
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return null
            val got = AtomicReference<Location?>()
            val done = CountDownLatch(1)
            val signal = CancellationSignal()
            lm.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, ctx.mainExecutor) { got.set(it); done.countDown() }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) signal.cancel()
            return got.get()?.takeIf { usable(it) }?.toFix()
        } catch (e: SecurityException) {
            return null
        }
    }

    private fun usable(l: Location) = !l.hasAccuracy() || l.accuracy <= Camera360Check.MAX_FIX_ACCURACY_M

    private fun Location.toFix() = PhoneFix(latitude, longitude, if (hasAltitude()) altitude else null,
        if (hasAccuracy()) accuracy.toDouble() else null)
}

/** EXIF GPS and thumbnail of the start of a JPEG (androidx ExifInterface; the APP1 segment comes first). */
object AndroidExifGps : ExifGpsReader {
    override fun read(jpegStart: ByteArray): ExifGps? = runCatching {
        val e = ExifInterface(ByteArrayInputStream(jpegStart))
        val ll = e.latLong
        ExifGps(ll?.getOrNull(0), ll?.getOrNull(1), runCatching { e.thumbnailBytes }.getOrNull())
    }.getOrNull()
}

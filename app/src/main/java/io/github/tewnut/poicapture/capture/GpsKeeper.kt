package io.github.tewnut.poicapture.capture

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper

/**
 * Keeps the GPS receiver running across screens. Android switches the GNSS chip off as soon as no app listens for
 * GPS, and switching it on again costs a new satellite search: seen on a MediaTek phone indoors, 29 s from the setup
 * screen's last fix to the capture service's first one, after a gap of only 0.25 s between the two listeners.
 * Screens and the capture service [acquire] it; after the last [release] it lingers [LINGER_MS] so that hand-over
 * (setup → capture, capture → sessions → setup) never drops the fix.
 */
object GpsKeeper {
    private const val LINGER_MS = 15_000L
    private val main = Handler(Looper.getMainLooper())
    private var holders = 0
    private var lm: LocationManager? = null
    private val listener = LocationListener { }   // the fixes go to the real listeners; this one only keeps GPS on
    private val stop = Runnable { if (holders == 0) { runCatching { lm?.removeUpdates(listener) }; lm = null } }

    @SuppressLint("MissingPermission")
    fun acquire(ctx: Context) = main.post {
        holders++
        main.removeCallbacks(stop)
        if (lm == null) {
            val m = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }.isSuccess) lm = m
        }
    }

    fun release() = main.post {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) { main.removeCallbacks(stop); main.postDelayed(stop, LINGER_MS) }
    }
}

package io.github.tewnut.poicapture.capture

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import io.github.tewnut.poicapture.core.HttpOscTransport
import io.github.tewnut.poicapture.core.OscClient
import io.github.tewnut.poicapture.core.OscException
import io.github.tewnut.poicapture.core.OscInfo
import io.github.tewnut.poicapture.core.ThetaOsc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * The phone's link to a Ricoh Theta on the camera's own Wi-Fi (docs/camera360.md §4), for the whole app (the setup
 * screen connects, the capture service shoots, the ride's end releases it).
 *
 * The network is asked for with a [WifiNetworkSpecifier] (Android shows its own "connect to device" dialog) and is
 * NOT bound to the process: only the OSC requests go over it ([Network.openConnection]), so mobile data keeps serving
 * the map, the road data and the uploads. The camera's network has no internet; the request drops that capability.
 * When the Wi-Fi drops the GPS ride goes on; the link asks again by itself after [RETRY_MS] (the system may show its
 * dialog again) and "Kết nối lại" asks at once.
 */
object ThetaLink {
    enum class Status { OFF, CONNECTING, CONNECTED, LOST, FAILED }

    data class Ui(val status: Status = Status.OFF, val model: String? = null, val batteryPct: Int? = null,
                  val clockSet: Boolean = false, val error: String? = null, val notes: List<String> = emptyList())

    private const val RETRY_MS = 15_000L
    private const val REQUEST_TIMEOUT_MS = 60_000
    private const val BATTERY_EVERY_MS = 60_000L

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state

    /** The camera's /osc/info from the last connect (for `session.camera360.camera`). */
    @Volatile var info: OscInfo? = null; private set
    /** A Theta ride is starting with this link: the setup screen leaving must not release it. */
    @Volatile var forRide = false

    @Volatile private var network: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var cm: ConnectivityManager? = null
    private var target: Pair<String, String>? = null      // (serial or SSID, password)
    @Volatile private var autoRetry = false
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val retry = Runnable { target?.let { (s, p) -> request(s, p) } }
    private val battery = object : Runnable {
        override fun run() {
            val n = network ?: return
            work.execute {
                runCatching { clientOn(n).state() }.getOrNull()?.batteryLevel?.let { b ->
                    _state.update { it.copy(batteryPct = (b * 100).toInt()) }
                }
            }
            main.postDelayed(this, BATTERY_EVERY_MS)
        }
    }

    private fun clientOn(n: Network) = OscClient(HttpOscTransport(ThetaOsc.BASE_URL) { url -> n.openConnection(url) })

    /** A client over the camera's network while connected, null otherwise (the shooter counts the trigger as skipped). */
    fun client(): OscClient? {
        val n = network ?: return null
        return if (_state.value.status == Status.CONNECTED) clientOn(n) else null
    }

    /**
     * Ask for the camera's Wi-Fi: [ssidOrSerial] is the network name (`THETAYR12345678.OSC`) or just its serial
     * digits; [password] empty = the serial digits (the Theta's default in direct mode). [keepTrying]: during a ride,
     * ask again after a drop or a refusal.
     */
    fun connect(ctx: Context, ssidOrSerial: String, password: String, keepTrying: Boolean = false) {
        cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        target = ssidOrSerial.trim() to password.ifBlank { ThetaOsc.passwordFrom(ssidOrSerial) }
        autoRetry = keepTrying
        main.removeCallbacks(retry)
        request(target!!.first, target!!.second)
    }

    /** During a ride: keep asking again after a drop (the setup screen does not). */
    fun keepTrying(on: Boolean) { autoRetry = on }

    /** Asked again by hand ("Kết nối lại"), with what was entered last. */
    fun reconnect(ctx: Context, ssidOrSerial: String, password: String) = connect(ctx, ssidOrSerial, password, autoRetry)

    private fun request(ssidOrSerial: String, password: String) {
        val cm = cm ?: return
        unregister()
        val digits = ThetaOsc.passwordFrom(ssidOrSerial)
        val spec = WifiNetworkSpecifier.Builder().apply {
            // the exact name when given; else any THETA network with these digits (the system dialog lists the matches)
            if (ssidOrSerial.startsWith(ThetaOsc.SSID_PREFIX, ignoreCase = true) && ssidOrSerial.contains('.')) setSsid(ssidOrSerial)
            else if (digits.isNotEmpty()) setSsidPattern(PatternMatcher("${ThetaOsc.SSID_PREFIX}.*$digits.*", PatternMatcher.PATTERN_SIMPLE_GLOB))
            else setSsidPattern(PatternMatcher(ThetaOsc.SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // the camera's Wi-Fi has none
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                _state.update { it.copy(status = Status.CONNECTING, error = null) }
                work.execute { handshake(n) }
            }

            override fun onLost(n: Network) {
                if (network != n) return
                network = null
                main.removeCallbacks(battery)
                _state.update { it.copy(status = Status.LOST) }
                if (autoRetry) main.postDelayed(retry, RETRY_MS)
            }

            override fun onUnavailable() {
                network = null
                _state.update { it.copy(status = Status.FAILED, error = "Wi-Fi") }
                if (autoRetry) main.postDelayed(retry, RETRY_MS)
            }
        }
        callback = cb
        _state.update { it.copy(status = Status.CONNECTING, error = null) }
        runCatching { cm.requestNetwork(req, cb, REQUEST_TIMEOUT_MS) }.onFailure { e ->
            callback = null
            _state.update { it.copy(status = Status.FAILED, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** The camera answered on its network: its info, its clock from the phone, the ride's options. */
    private fun handshake(n: Network) {
        try {
            val r = clientOn(n).connect(System.currentTimeMillis(), ZoneId.systemDefault())
            if (network != n) return
            info = r.info
            _state.value = Ui(Status.CONNECTED, r.info.model, r.batteryLevel?.let { (it * 100).toInt() }, r.clockSet, null, r.notes)
            main.removeCallbacks(battery)
            main.postDelayed(battery, BATTERY_EVERY_MS)
        } catch (e: OscException) {
            if (network == n) _state.update { it.copy(status = Status.FAILED, error = e.short()) }
        }
    }

    private fun unregister() {
        callback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        callback = null
        network = null
        main.removeCallbacks(battery)
    }

    /** Let the camera's Wi-Fi go (the phone goes back to its usual networks). */
    fun release() {
        autoRetry = false
        forRide = false
        main.removeCallbacks(retry)
        unregister()
        _state.value = Ui()
    }
}

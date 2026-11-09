package io.github.tewnut.poicapture.ui

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.tewnut.poicapture.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.core.LatLon
import io.github.tewnut.poicapture.core.PhotoRoles
import io.github.tewnut.poicapture.core.PoiStatus

/** Category chips: the ids of SignText (exported as `fields.category`), shown with their translated labels. */
private val CATEGORIES = listOf("restaurant", "cafe", "grocery", "fashion", "phone_shop", "vehicle_repair", "pharmacy", "beauty", "services")

/**
 * What the contributor typed for the current place. Lives in the capture screen, so "Chụp thêm" (which closes the sheet
 * to take more photos) keeps it; a new place starts with a new draft.
 */
class PoiDraft {
    var pin by mutableStateOf<LatLon?>(null)
    var moved by mutableStateOf(false)
    var name by mutableStateOf("")
    var category by mutableStateOf("")
    var house by mutableStateOf("")
    var phone by mutableStateOf("")
    var note by mutableStateOf("")
    var status by mutableStateOf(PoiStatus.NEW)
    fun fields() = mapOf("name" to name, "category" to category, "houseNumber" to house, "phone" to phone, "note" to note)
}

/**
 * End of a place: put the pin on the building (tap or drag) and, if it is not a new open shop, its status. Nothing to
 * type: name, address, phone and category are read from the photos (on the phone when it can, then by the server's
 * agent). The fields stay behind "Ghi chú thêm" for what a photo cannot show (sign hidden, shop without a sign).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoiSheet(number: Int, start: LatLon?, roles: Map<String, Int>, photoRoles: PhotoRoles, d: PoiDraft,
             read: io.github.tewnut.poicapture.core.SignReading?, reading: Boolean,
             onMore: () -> Unit, onSave: (pin: LatLon?, moved: Boolean, fields: Map<String, String>, status: PoiStatus) -> Unit) {
    val ctx = LocalContext.current
    if (d.pin == null && !d.moved) d.pin = start
    // no GPS fix for this place yet: open the map where the phone was last seen, not at 0,0
    val centre = remember { start ?: lastKnown(ctx) }
    val noFront = (roles[photoRoles.cover.id] ?: 0) == 0

    Row(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            PinMap(d.pin, centre, onPin = { d.pin = it; d.moved = true }, modifier = Modifier.fillMaxSize().clipToBounds())
            Pill(stringResource(when {
                    d.moved -> R.string.poi_pin_set
                    d.pin == null -> R.string.poi_pin_no_gps
                    else -> R.string.poi_pin_move
                }),
                Modifier.align(Alignment.TopCenter).padding(10.dp), size = 13.sp,
                dot = if (d.moved) Color(0xFF47CD89) else Color(0xFFFEC84B))
        }
        // everything scrolls (landscape screens are short, the keyboard takes half); the two actions stay pinned
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 8.dp)) {
          Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.poi_title, number), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Palette.Ink)
            val tagged = roles.keys.any { it.isNotEmpty() }
            val noPhotos = stringResource(R.string.poi_no_photos)
            if (!tagged) Hint(roles.values.sum().let { if (it == 0) noPhotos else pluralStringResource(R.plurals.poi_photos, it, it) })
            else Hint(roles.entries.sortedBy { e -> photoRoles.roles.indexOfFirst { it.id == e.key }.let { if (it < 0) 99 else it } }
                .joinToString(" · ") { "${roleLabel(ctx, photoRoles, it.key)} ${it.value}" }.ifEmpty { noPhotos })
            if (tagged && noFront) Hint(stringResource(R.string.poi_no_cover, roleLabel(photoRoles, photoRoles.cover)), color = Palette.Amber)
            Text(stringResource(R.string.poi_nothing_to_type), color = Palette.Green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth().background(Palette.GreenLight, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.poi_read_title), fontWeight = FontWeight.Bold, color = Palette.Ink)
                val rows = listOfNotNull(read?.name?.let { ctx.getString(R.string.poi_read_name, it) },
                    read?.phones?.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.poi_read_phone, it.joinToString(", ")) },
                    read?.houseNumber?.let { ctx.getString(R.string.poi_read_house, it) },
                    read?.category?.let { ctx.getString(R.string.poi_read_category, categoryLabel(ctx, it)) })
                when {
                    reading -> Hint(stringResource(R.string.poi_reading))
                    rows.isEmpty() -> Hint(stringResource(R.string.poi_read_nothing))
                    else -> rows.forEach { Text(it, color = Palette.Ink) }
                }
                Hint(stringResource(R.string.poi_read_hint))
            }
            Text(stringResource(R.string.poi_status), fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (st in PoiStatus.entries) FilterChip(d.status == st, { d.status = st }, { Text(stringResource(st.labelRes())) })
            }
            var more by remember { mutableStateOf(d.fields().values.any { it.isNotBlank() }) }
            androidx.compose.material3.TextButton(onClick = { more = !more }) {
                Text(stringResource(if (more) R.string.poi_more_open else R.string.poi_more_closed))
            }
            if (more) {
                OutlinedTextField(d.note, { d.note = it }, label = { Text(stringResource(R.string.poi_note)) }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.poi_note_placeholder)) })
                OutlinedTextField(d.name, { d.name = it }, label = { Text(stringResource(R.string.poi_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.phone, { d.phone = it }, label = { Text(stringResource(R.string.poi_phone)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(d.house, { d.house = it }, label = { Text(stringResource(R.string.poi_house)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in CATEGORIES) FilterChip(d.category == c, { d.category = if (d.category == c) "" else c }, { Text(categoryLabel(c)) })
                }
            }
          }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onMore, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.poi_shoot_more), maxLines = 1) }
                Button(onClick = { onSave(d.pin, d.moved, d.fields(), d.status) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(stringResource(R.string.poi_save_next), fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@android.annotation.SuppressLint("MissingPermission")
private fun lastKnown(ctx: android.content.Context): LatLon? = runCatching {
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
        ?.let { LatLon(it.latitude, it.longitude) }
}.getOrNull()

/** Map with one draggable pin; tapping the map moves the pin there. */
@Composable
private fun PinMap(pin: LatLon?, centre: LatLon?, onPin: (LatLon) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val setPin by rememberUpdatedState(onPin)
    val density = ctx.resources.displayMetrics.density
    val icon = remember {
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFD92D20.toInt())
            setStroke((3 * density).toInt(), 0xFFFFFFFF.toInt())
            setSize((24 * density).toInt(), (24 * density).toInt())
        }
    }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TemplateTileSource(Prefs.settings(ctx).tileUrl))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            maxZoomLevel = 19.0
            controller.setZoom(19.0)
            (pin ?: centre)?.let { controller.setCenter(GeoPoint(it.lat, it.lon)) }
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); map.onDetach() }
    }
    AndroidView({ map }, modifier) { m ->
        m.overlays.clear()
        m.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { setPin(LatLon(p.latitude, p.longitude)); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        pin?.let { p ->
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(p.lat, p.lon)
                this.icon = icon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                isDraggable = true
                setOnMarkerClickListener { _, _ -> true }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker) {}
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDragEnd(marker: Marker) = setPin(LatLon(marker.position.latitude, marker.position.longitude))
                })
            })
        }
        m.invalidate()
    }
}

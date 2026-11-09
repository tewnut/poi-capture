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

private val CATEGORIES = listOf("Quán ăn", "Cà phê", "Tạp hoá", "Thời trang", "Điện thoại", "Sửa xe", "Thuốc", "Làm đẹp", "Dịch vụ")

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
            Pill(when {
                    d.moved -> "Đã đặt ghim lên tòa nhà ✓"
                    d.pin == null -> "Chưa có GPS: chạm vào tòa nhà để đặt ghim"
                    else -> "Chạm hoặc kéo ghim lên đúng tòa nhà"
                },
                Modifier.align(Alignment.TopCenter).padding(10.dp), size = 13.sp,
                dot = if (d.moved) Color(0xFF47CD89) else Color(0xFFFEC84B))
        }
        // everything scrolls (landscape screens are short, the keyboard takes half); the two actions stay pinned
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 8.dp)) {
          Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Địa điểm $number", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Palette.Ink)
            val tagged = roles.keys.any { it.isNotEmpty() }
            if (!tagged) Hint(roles.values.sum().let { if (it == 0) "Chưa có ảnh" else "$it ảnh" })
            else Hint(roles.entries.sortedBy { e -> photoRoles.roles.indexOfFirst { it.id == e.key }.let { if (it < 0) 99 else it } }
                .joinToString(" · ") { "${photoRoles.label(it.key)} ${it.value}" }.ifEmpty { "Chưa có ảnh" })
            if (tagged && noFront) Hint("Chưa có ảnh “${photoRoles.cover.label}” (ảnh bìa). Nên chụp thêm một ảnh.", color = Palette.Amber)
            Text("Không cần nhập gì: chỉ cần đặt ghim đúng tòa nhà.", color = Palette.Green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth().background(Palette.GreenLight, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Đọc được từ ảnh", fontWeight = FontWeight.Bold, color = Palette.Ink)
                val rows = listOfNotNull(read?.name?.let { "Tên: $it" }, read?.phones?.takeIf { it.isNotEmpty() }?.let { "Điện thoại: ${it.joinToString(", ")}" },
                    read?.houseNumber?.let { "Số nhà: $it" }, read?.category?.let { "Loại hình: $it" })
                when {
                    reading -> Hint("Đang đọc chữ trên ảnh…")
                    rows.isEmpty() -> Hint("Máy chưa đọc được gì. Hệ thống sẽ đọc lại ảnh trên máy chủ.")
                    else -> rows.forEach { Text(it, color = Palette.Ink) }
                }
                Hint("Gợi ý cho hệ thống; tên, địa chỉ, loại hình được kiểm tra lại với ảnh và bản đồ.")
            }
            Text("Tình trạng", fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (st in PoiStatus.entries) FilterChip(d.status == st, { d.status = st }, { Text(st.label) })
            }
            var more by remember { mutableStateOf(d.fields().values.any { it.isNotBlank() }) }
            androidx.compose.material3.TextButton(onClick = { more = !more }) {
                Text(if (more) "▾ Ghi chú thêm" else "▸ Ghi chú thêm (không bắt buộc: biển bị che, không có biển…)")
            }
            if (more) {
                OutlinedTextField(d.note, { d.note = it }, label = { Text("Ghi chú cho hệ thống") }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("vd: biển hiệu bị cây che, quán không có biển") })
                OutlinedTextField(d.name, { d.name = it }, label = { Text("Tên (nếu ảnh không thấy)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.phone, { d.phone = it }, label = { Text("Số điện thoại") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(d.house, { d.house = it }, label = { Text("Số nhà") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in CATEGORIES) FilterChip(d.category == c, { d.category = if (d.category == c) "" else c }, { Text(c) })
                }
            }
          }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onMore, modifier = Modifier.height(48.dp)) { Text("Chụp thêm", maxLines = 1) }
                Button(onClick = { onSave(d.pin, d.moved, d.fields(), d.status) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Lưu & tiếp", fontWeight = FontWeight.Bold, maxLines = 1)
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

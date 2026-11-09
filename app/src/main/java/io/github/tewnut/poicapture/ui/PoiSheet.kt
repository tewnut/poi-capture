package io.github.tewnut.poicapture.ui

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import io.github.tewnut.poicapture.core.PhotoRole
import io.github.tewnut.poicapture.core.PoiStatus

private val CATEGORIES = listOf("Quán ăn", "Cà phê", "Tạp hoá", "Thời trang", "Điện thoại", "Sửa xe", "Thuốc", "Làm đẹp", "Dịch vụ")

/**
 * End of a place: put the pin on the building (tap or drag), optionally note what is readable on site, set the status.
 * Everything except the pin is optional: the agent reads the sign on the photos anyway.
 */
@Composable
fun PoiSheet(number: Int, start: LatLon?, roles: Map<String, Int>,
             onMore: () -> Unit, onSave: (pin: LatLon?, moved: Boolean, fields: Map<String, String>, status: PoiStatus) -> Unit) {
    var pin by remember { mutableStateOf(start) }
    var moved by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var house by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(PoiStatus.NEW) }
    val noFront = (roles[PhotoRole.FACADE.json] ?: 0) == 0

    Row(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            PinMap(pin, onPin = { pin = it; moved = true }, modifier = Modifier.fillMaxSize().clipToBounds())
            Pill(if (moved) "Đã đặt ghim lên tòa nhà ✓" else "Chạm hoặc kéo ghim lên đúng tòa nhà",
                Modifier.align(Alignment.TopCenter).padding(10.dp), size = 13.sp,
                dot = if (moved) Color(0xFF47CD89) else Color(0xFFFEC84B))
        }
        // fields scroll; status and the two actions stay pinned at the bottom (landscape screens are short)
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 8.dp)) {
          Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Địa điểm $number", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Palette.Ink)
            Hint(PhotoRole.entries.mapNotNull { r -> roles[r.json]?.let { "${r.label} $it" } }.joinToString(" · ").ifEmpty { "Chưa có ảnh" })
            if (noFront) Hint("Chưa có ảnh mặt tiền (ảnh bìa). Nên chụp thêm một ảnh trực diện.", color = Palette.Amber)
            Hint("Không bắt buộc: ghi những gì đọc được tại chỗ, phần còn lại agent đọc từ ảnh.")
            OutlinedTextField(name, { name = it }, label = { Text("Tên trên biển hiệu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(category, { category = it }, label = { Text("Loại hình") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (c in CATEGORIES.take(5)) FilterChip(category == c, { category = c }, { Text(c, fontSize = 12.sp) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(house, { house = it }, label = { Text("Số nhà") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(phone, { phone = it }, label = { Text("Số điện thoại") }, singleLine = true, modifier = Modifier.weight(1.4f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            }
            OutlinedTextField(note, { note = it }, label = { Text("Ghi chú") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tình trạng:", fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                for (s in PoiStatus.entries) FilterChip(status == s, { status = s }, { Text(s.label, fontSize = 12.sp) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onMore) { Text("Chụp thêm") }
                Button(onClick = {
                    onSave(pin, moved, mapOf("name" to name, "category" to category, "houseNumber" to house, "phone" to phone, "note" to note), status)
                }, modifier = Modifier.weight(1f)) { Text("Lưu, sang địa điểm tiếp", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Map with one draggable pin; tapping the map moves the pin there. */
@Composable
private fun PinMap(pin: LatLon?, onPin: (LatLon) -> Unit, modifier: Modifier) {
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
            pin?.let { controller.setCenter(GeoPoint(it.lat, it.lon)) }
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

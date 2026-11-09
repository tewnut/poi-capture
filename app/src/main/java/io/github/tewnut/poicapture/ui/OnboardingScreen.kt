package io.github.tewnut.poicapture.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.capture.RoadPackCatalogue
import io.github.tewnut.poicapture.capture.RoadPackDownloads
import io.github.tewnut.poicapture.core.CapturePlan
import io.github.tewnut.poicapture.core.Onboarding
import io.github.tewnut.poicapture.net.RoadSource
import java.util.Locale
import kotlin.math.roundToInt

private enum class Step { WELCOME, EMAIL, PERMISSIONS, ROADS, READY }

/**
 * First run, once per phone: what the app does → email (not verified) → permissions → offline road data for where the
 * contributor is → mounting and facade distance. Everything here can be changed later in Hồ sơ.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    BackHandler(enabled = step != Step.WELCOME) { step = Step.entries[step.ordinal - 1] }

    Column(Modifier.fillMaxSize().background(Color(0xFFF9FAFB)).safeDrawingPadding()) {
        // top bar: back + progress
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step != Step.WELCOME) TextButton(onClick = { step = Step.entries[step.ordinal - 1] }) { Text("← Quay lại") }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 12.dp)) {
                for (s in Step.entries) {
                    Box(Modifier.size(width = if (s == step) 22.dp else 8.dp, height = 8.dp)
                        .background(if (s.ordinal <= step.ordinal) Palette.Green else Palette.House, RoundedCornerShape(50)))
                }
            }
        }
        when (step) {
            Step.WELCOME -> WelcomeStep { step = Step.EMAIL }
            Step.EMAIL -> EmailStep(ctx) { step = Step.PERMISSIONS }
            Step.PERMISSIONS -> PermissionsStep(ctx) { step = Step.ROADS }
            Step.ROADS -> RoadsStep(ctx) { step = Step.READY }
            Step.READY -> ReadyStep(ctx) { Prefs.setOnboarded(ctx, true); onDone() }
        }
    }
}

/** Scrolling body + the primary button pinned at the bottom (and an optional secondary action above it). */
@Composable
private fun StepFrame(title: String, lead: String?, button: String, enabled: Boolean = true, onNext: () -> Unit,
                      secondary: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            if (lead != null) Text(lead, fontSize = 16.sp, color = Palette.Muted)
            content()
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            secondary?.invoke()
            Button(onClick = onNext, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(button, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun IconRow(icon: String, title: String, text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).background(Palette.GreenLight, CircleShape), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Ink)
            Hint(text)
        }
        trailing?.invoke()
    }
}

@Composable
private fun WhiteCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

// ---------------------------------------------------------------- 1. welcome

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepFrame("POI Capture", "Ghi lại cửa hàng, quán xá trên phố để đưa lên bản đồ.", "Bắt đầu thiết lập", onNext = onNext,
        secondary = { Hint("Thiết lập một lần, khoảng 1 phút.", Modifier.padding(bottom = 8.dp)) }) {
        Spacer(Modifier.height(4.dp))
        IconRow("🛵", "Quét đường", "Gắn điện thoại lên xe, chạy dọc phố: app tự chụp dãy nhà bên phải, bạn không phải chạm máy.")
        IconRow("🏪", "Chụp địa điểm", "Dừng ở từng cửa hàng: chụp mặt tiền, biển hiệu, bên trong, rồi ghim lên đúng tòa nhà.")
        IconRow("📍", "Vị trí đặt vào tòa nhà", "App tính vị trí mặt tiền từ GPS và hướng camera. Sai chỗ nào, kéo lại trên bản đồ.")
        IconRow("📶", "Không cần mạng khi ghi", "Ảnh lưu trên máy, tự tải lên khi có Wi-Fi hoặc gửi file qua Zalo / Drive.")
    }
}

// ---------------------------------------------------------------- 2. email

@Composable
private fun EmailStep(ctx: Context, onNext: () -> Unit) {
    var email by rememberSaveable { mutableStateOf(Prefs.email(ctx)) }
    var name by rememberSaveable { mutableStateOf(Prefs.contributor(ctx)) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val valid = Onboarding.isEmail(email)
    fun next() {
        if (!valid) { touched = true; return }
        Prefs.saveEmail(ctx, email.trim())
        Prefs.saveContributor(ctx, name.trim())
        onNext()
    }
    StepFrame("Email của bạn", "Dùng để ghi công và gộp các phiên ghi của bạn trên máy chủ. Không cần xác minh, app không gửi thư.",
        "Tiếp tục", enabled = email.isNotBlank(), onNext = ::next) {
        OutlinedTextField(email, { email = it.trim(); if (touched && Onboarding.isEmail(email)) touched = false },
            label = { Text("Email") }, placeholder = { Text("ten@gmail.com") }, singleLine = true,
            isError = touched && !valid,
            supportingText = { if (touched && !valid) Text("Email chưa đúng dạng, ví dụ ten@gmail.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Tên hiển thị (không bắt buộc)") },
            placeholder = { Text(email.substringBefore('@').ifBlank { "Nguyễn Văn A" }) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            supportingText = { Text("Hiện trên các phiên ghi. Để trống thì dùng phần trước @ của email.") },
            modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- 3. permissions

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private data class Perm(val icon: String, val title: String, val why: String, val names: List<String>)

private fun perms(): List<Perm> = buildList {
    add(Perm("📷", "Camera", "Chụp ảnh và quay video mặt tiền.", listOf(Manifest.permission.CAMERA)))
    add(Perm("📍", "Vị trí chính xác", "Biết mỗi ảnh chụp ở đâu và bạn đang ở đường nào.",
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
    if (Build.VERSION.SDK_INT >= 33) {
        add(Perm("🔔", "Thông báo", "Hiện trạng thái đang ghi khi màn hình tắt.", listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }
}

@Composable
private fun PermissionsStep(ctx: Context, onNext: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true; tick++ }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val list = remember { perms() }
    // re-read the grants after each request / return from Settings
    val missing = remember(tick) { list.filter { p -> !granted(ctx, p.names.first()) } }
    val required = missing.filter { it.icon != "🔔" }

    StepFrame("Cho phép app dùng", "App chỉ dùng những quyền này khi bạn đang ghi.",
        if (missing.isEmpty()) "Tiếp tục" else "Cấp quyền",
        onNext = { if (missing.isEmpty()) onNext() else request.launch(missing.flatMap { it.names }.toTypedArray()) },
        secondary = { if (asked && missing.isNotEmpty()) TextButton(onClick = onNext) { Text("Để sau") } }) {
        WhiteCard {
            for (p in list) {
                val ok = p !in missing
                IconRow(p.icon, p.title, p.why) {
                    Text(if (ok) "✓" else "Chưa cấp", color = if (ok) Palette.Green else Palette.Amber,
                        fontWeight = FontWeight.Bold, fontSize = if (ok) 20.sp else 13.sp)
                }
            }
        }
        if (asked && required.isNotEmpty()) {
            Hint("Chưa có ${required.joinToString(" và ") { it.title.lowercase(Locale.ROOT) }} thì chưa ghi được. " +
                "Nếu Android không hỏi lại, mở Cài đặt → Quyền để bật.", color = Palette.Amber)
            TextButton(onClick = {
                settings.launch(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }) { Text("Mở Cài đặt của app") }
        }
    }
}

// ---------------------------------------------------------------- 4. road data

@Composable
private fun RoadsStep(ctx: Context, onNext: () -> Unit) {
    val url = remember { Prefs.settings(ctx).roadPackIndexUrl }
    var catalogue by remember { mutableStateOf(RoadSource.cachedCatalogue(ctx)) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf(emptyMap<String, String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        installed = withContext(Dispatchers.IO) { RoadSource.packs(ctx).installed().associate { it.id to it.version } }
    }
    LaunchedEffect(attempt) {
        if (url.isBlank()) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        try { catalogue = withContext(Dispatchers.IO) { RoadPackCatalogue.fetch(ctx, url) } }
        catch (e: Exception) { error = e.message ?: "lỗi mạng" }
        loading = false
    }
    val live = rememberLiveLocation()
    val suggestion = live?.let { RoadSource.suggest(catalogue, it.lat, it.lon) }
    // the province the contributor is in: download its pack right away (once), no tap needed
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(suggestion?.id, installed) {
        val s = suggestion ?: return@LaunchedEffect
        if (!autoStarted && installed[s.id] != s.version) {
            RoadPackDownloads.enqueue(ctx, s, anyNetwork = true)
            autoStarted = true
        }
    }
    var showAll by rememberSaveable { mutableStateOf(false) }

    StepFrame("Dữ liệu đường", "Dữ liệu đường của tỉnh / thành bạn đang ở giúp app nhận tên đường ngay cả khi mất mạng.",
        "Tiếp tục", onNext = onNext) {
        when {
            loading && catalogue.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Hint("Đang tải danh sách tỉnh / thành…")
            }
            error != null && catalogue.isEmpty() -> {
                Hint("Không tải được danh sách: $error", color = Palette.Red)
                Row {
                    TextButton(onClick = { attempt++ }) { Text("Thử lại") }
                    Hint("hoặc bỏ qua, tải sau ở Hồ sơ.", Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        if (suggestion != null) {
            WhiteCard {
                Text("Bạn đang ở ${suggestion.name}", fontWeight = FontWeight.Bold, color = Palette.Green, fontSize = 17.sp)
                PackRow(suggestion, installed[suggestion.id], anyNetwork = true, onInstalled = { refresh++ })
                Hint(if (installed[suggestion.id] == suggestion.version) "Đã có dữ liệu đường, sẵn sàng ghi."
                    else "Đang tự tải (cả khi dùng 4G). Gói tải tiếp ở nền, bạn có thể đi tiếp.")
            }
        } else if (catalogue.isNotEmpty()) {
            Hint(if (live == null) "Đang chờ vị trí để biết bạn ở tỉnh / thành nào… hoặc chọn bên dưới."
                else "Vị trí hiện tại không thuộc tỉnh / thành nào có gói: chọn bên dưới.")
        }
        val others = catalogue.filter { it.id != suggestion?.id }.sortedBy { it.name }
        if (others.isNotEmpty()) {
            if (suggestion != null && !showAll) {
                TextButton(onClick = { showAll = true }) { Text("Chọn tỉnh / thành khác (${others.size})") }
            } else WhiteCard {
                Text(if (suggestion != null) "Tỉnh / thành khác" else "Các tỉnh / thành", fontWeight = FontWeight.Bold, color = Palette.Ink)
                for (e in others) PackRow(e, installed[e.id], anyNetwork = true, onInstalled = { refresh++ })
            }
        }
        if (catalogue.isNotEmpty()) Hint("Dữ liệu đường © OpenStreetMap contributors (ODbL), qua Geofabrik. Quản lý gói ở Hồ sơ.")
    }
}

// ---------------------------------------------------------------- 5. ready

@Composable
private fun ReadyStep(ctx: Context, onDone: () -> Unit) {
    var settings by remember { mutableStateOf(Prefs.settings(ctx)) }
    val email = remember { Prefs.email(ctx) }
    val name = remember { Onboarding.displayName(Prefs.contributor(ctx), email) }
    val missing = perms().filter { p -> !granted(ctx, p.names.first()) && p.icon != "🔔" }
    val packs = remember { RoadSource.packs(ctx).installed().map { it.name } }
    val plan = CapturePlan.of(settings.facadeDistanceM)

    StepFrame("Sẵn sàng!", null, "Bắt đầu ghi", onNext = { Prefs.saveSettings(ctx, settings); onDone() }) {
        WhiteCard {
            Text("Mặt tiền thường cách camera", fontWeight = FontWeight.Bold, color = Palette.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in listOf(4.0, 6.0, 8.0, 12.0)) {
                    FilterChip(settings.facadeDistanceM == d, { settings = settings.copy(facadeDistanceM = d) }, { Text("${d.roundToInt()} m") })
                }
            }
            Hint("Phố hẹp, đi sát lề: 4 m · phố thường: 6 m · vỉa hè rộng: 8 m · đại lộ: 12 m. " +
                "→ 1 ảnh mỗi ${String.format(Locale.ROOT, "%.1f", plan.photoEveryM)} m, chạy tối đa ${plan.maxSpeedKmh} km/h. Đổi được ở mỗi lần ghi.")
        }
        WhiteCard {
            Text("Gắn máy", fontWeight = FontWeight.Bold, color = Palette.Ink)
            Hint("• Điện thoại nằm ngang, kẹp chắc trên giá xe.\n" +
                "• Camera sau nhìn sang dãy nhà bên phải.\n" +
                "• Vài lần tăng tốc / phanh đầu tiên, app tự đo hướng camera.")
        }
        WhiteCard {
            Summary(true, "Người ghi: $name", email)
            Summary(missing.isEmpty(), if (missing.isEmpty()) "Đã cấp quyền camera và vị trí"
                else "Còn thiếu quyền ${missing.joinToString(", ") { it.title.lowercase(Locale.ROOT) }}", if (missing.isEmpty()) null else "Bật ở Cài đặt trước khi ghi.")
            Summary(packs.isNotEmpty(), if (packs.isNotEmpty()) "Dữ liệu đường: ${packs.joinToString(", ")}" else "Dữ liệu đường: lấy qua mạng khi ghi",
                if (packs.isNotEmpty()) null else "Gói đang tải (nếu bạn đã chọn) sẽ dùng được khi tải xong.")
        }
        Hint("Ghi xong, vào “Đã ghi” để xem lại trên bản đồ và gửi (Zalo, Drive, email…).")
    }
}

@Composable
private fun Summary(ok: Boolean, title: String, sub: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ok) "✓" else "!", color = if (ok) Palette.Green else Palette.Amber, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.width(18.dp))
        Column {
            Text(title, color = Palette.Ink, fontWeight = FontWeight.Medium)
            if (sub != null) Hint(sub)
        }
    }
}

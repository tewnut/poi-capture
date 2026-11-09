package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.core.CapturePlan
import io.github.tewnut.poicapture.core.Onboarding
import androidx.compose.ui.text.input.KeyboardType

/** Profile (set up by the onboarding): who records, the facade distance, mounting, road data, server. */
@Composable
fun ProfileScreen(onDone: () -> Unit, onReplayOnboarding: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Prefs.contributor(ctx)) }
    var email by remember { mutableStateOf(Prefs.email(ctx)) }
    var settings by remember { mutableStateOf(Prefs.settings(ctx)) }
    val emailOk = Onboarding.isEmail(email)

    fun save() {
        Prefs.saveContributor(ctx, name.trim())
        if (emailOk) Prefs.saveEmail(ctx, email.trim())
        Prefs.saveSettings(ctx, settings)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Hồ sơ", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)

        ProfileCard("Người ghi") {
            OutlinedTextField(email, { email = it.trim(); save() }, label = { Text("Email") }, singleLine = true,
                isError = !emailOk,
                supportingText = { Text(if (emailOk) "Không cần xác minh. Dùng để ghi công và gộp các phiên ghi của bạn."
                    else "Email chưa đúng dạng, ví dụ ten@gmail.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it; save() }, label = { Text("Tên hiển thị (không bắt buộc)") }, singleLine = true,
                placeholder = { Text(email.substringBefore('@')) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
        }

        ProfileCard("Khoảng cách tới mặt tiền (mặc định)") {
            Hint("Từ camera tới mặt tiền nhà (không phải tới mép đường), thường gặp khi bạn chạy làn sát lề. " +
                "Dùng để tính khoảng cách giữa các ảnh, tốc độ tối đa và đặt địa điểm đúng vào tòa nhà. Chỉnh lại được ở mỗi lần ghi.")
            val plan = CapturePlan.of(settings.facadeDistanceM)
            Stepper("${settings.facadeDistanceM.toInt()} m",
                onMinus = { settings = settings.copy(facadeDistanceM = (settings.facadeDistanceM - 1).coerceAtLeast(2.0)); save() },
                onPlus = { settings = settings.copy(facadeDistanceM = (settings.facadeDistanceM + 1).coerceAtMost(20.0)); save() },
                caption = "1 ảnh mỗi ${String.format(java.util.Locale.ROOT, "%.1f", plan.photoEveryM)} m · tối đa ${plan.maxSpeedKmh} km/h",
                minus = "−1 m", plus = "+1 m",
                minusEnabled = settings.facadeDistanceM > 2.0, plusEnabled = settings.facadeDistanceM < 20.0)
        }

        ProfileCard("Loại ảnh khi chụp địa điểm") { PhotoRolesCard() }

        ProfileCard("Dữ liệu đường offline") { RoadPacksCard() }

        var advanced by remember { mutableStateOf(false) }
        androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "▾ Ẩn cài đặt nâng cao" else "▸ Cài đặt nâng cao (góc giá đỡ, máy chủ)")
        }
        if (advanced) ProfileCard("Góc chếch giá đỡ (chỉnh tay)") {
            Hint("App tự đo hướng camera khi bạn tăng tốc / phanh vài lần. Chỉ chỉnh ở đây nếu app báo chưa đo được: " +
                "camera hơi chếch về phía trước hay phía sau bao nhiêu độ.")
            val deg = settings.mountOffsetDeg.toInt()
            Stepper(if (deg == 0) "0°" else "${kotlin.math.abs(deg)}°",
                onMinus = { settings = settings.copy(mountOffsetDeg = (settings.mountOffsetDeg - 5).coerceAtLeast(-45.0)); save() },
                onPlus = { settings = settings.copy(mountOffsetDeg = (settings.mountOffsetDeg + 5).coerceAtMost(45.0)); save() },
                caption = when { deg > 0 -> "chếch về phía trước"; deg < 0 -> "chếch về phía sau"; else -> "vuông góc với đường" },
                minus = "−5°", plus = "+5°")
        }

        if (advanced) ProfileCard("Máy chủ nhận dữ liệu") {
            Hint("Do quản lý cung cấp. Có máy chủ thì các phiên tự tải lên khi có Wi-Fi (tiếp tục được nếu mất mạng); " +
                "không có thì gửi file zip qua Zalo / Drive như trước.")
            OutlinedTextField(settings.uploadUrl, { settings = settings.copy(uploadUrl = it.trim()); save() },
                label = { Text("Địa chỉ tải lên (tus)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://…/files/") })
            OutlinedTextField(settings.uploadToken, { settings = settings.copy(uploadToken = it.trim()); save() },
                label = { Text("Mã truy cập") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            OutlinedTextField(settings.apiUrl, { settings = settings.copy(apiUrl = it.trim()); save() },
                label = { Text("Địa chỉ API (không bắt buộc)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("trạng thái xử lý, tuyến đã quét của nhóm") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cho phép tải bằng 4G", modifier = Modifier.weight(1f), color = Palette.Ink)
                androidx.compose.material3.Switch(settings.uploadOnMobileData, { settings = settings.copy(uploadOnMobileData = it); save() })
            }
        }

        Button(onClick = {
            save()
            Thread { io.github.tewnut.poicapture.capture.Uploads.enqueuePending(ctx) }.start()   // pick up waiting sessions with the new settings
            onDone()
        }, enabled = emailOk, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Xong", fontWeight = FontWeight.Bold)
        }
        androidx.compose.material3.TextButton(onClick = onReplayOnboarding, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Xem lại phần giới thiệu")
        }
    }
}

@Composable
private fun ProfileCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Palette.Ink)
            content()
        }
    }
}

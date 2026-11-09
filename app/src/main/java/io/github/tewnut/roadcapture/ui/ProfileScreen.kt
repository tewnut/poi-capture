package io.github.tewnut.roadcapture.ui

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
import io.github.tewnut.roadcapture.Prefs
import io.github.tewnut.roadcapture.core.CapturePlan

/** One-time profile: who records, on what, and how the phone is mounted. */
@Composable
fun ProfileScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val firstRun = remember { Prefs.contributor(ctx).isBlank() }
    var name by remember { mutableStateOf(Prefs.contributor(ctx)) }
    var settings by remember { mutableStateOf(Prefs.settings(ctx)) }

    fun save() {
        Prefs.saveContributor(ctx, name.trim())
        Prefs.saveSettings(ctx, settings)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (firstRun) "Chào bạn!" else "Hồ sơ", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        if (firstRun) Hint("Thiết lập một lần cho máy này. Sau đó mỗi lần ghi chỉ cần chọn bên đường và bấm Bắt đầu.")

        ProfileCard("Người ghi") {
            OutlinedTextField(name, { name = it; save() }, label = { Text("Tên hoặc mã cộng tác viên") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
        }

        ProfileCard("Khoảng cách tới mặt tiền (mặc định)") {
            Hint("Từ camera tới mặt tiền nhà (không phải tới mép đường), thường gặp khi bạn chạy làn sát lề. " +
                "Dùng để tính khoảng cách giữa các ảnh, tốc độ tối đa và đặt địa điểm đúng vào tòa nhà. Chỉnh lại được ở mỗi lần ghi.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { settings = settings.copy(facadeDistanceM = (settings.facadeDistanceM - 1).coerceAtLeast(2.0)); save() }) { Text("−1 m") }
                Text("${settings.facadeDistanceM.toInt()} m", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.width(70.dp))
                OutlinedButton(onClick = { settings = settings.copy(facadeDistanceM = (settings.facadeDistanceM + 1).coerceAtMost(20.0)); save() }) { Text("+1 m") }
            }
            val plan = CapturePlan.of(settings.facadeDistanceM)
            Hint("→ 1 ảnh mỗi ${String.format(java.util.Locale.ROOT, "%.1f", plan.photoEveryM)} m, chạy tối đa ${plan.maxSpeedKmh} km/h.")
        }

        ProfileCard("Góc chếch giá đỡ (chỉnh tay)") {
            Hint("App tự đo hướng camera khi bạn tăng tốc / phanh vài lần. Chỉ chỉnh ở đây nếu app báo chưa đo được: " +
                "camera hơi chếch về phía trước hay phía sau bao nhiêu độ.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { settings = settings.copy(mountOffsetDeg = settings.mountOffsetDeg - 5); save() }) { Text("−5°") }
                Text(
                    when {
                        settings.mountOffsetDeg > 0 -> "Chếch ${settings.mountOffsetDeg.toInt()}° về trước"
                        settings.mountOffsetDeg < 0 -> "Chếch ${-settings.mountOffsetDeg.toInt()}° về sau"
                        else -> "Vuông góc (0°)"
                    },
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.width(150.dp),
                )
                OutlinedButton(onClick = { settings = settings.copy(mountOffsetDeg = settings.mountOffsetDeg + 5); save() }) { Text("+5°") }
            }
        }

        ProfileCard("Dữ liệu đường offline") { RoadPacksCard() }

        ProfileCard("Máy chủ nhận dữ liệu") {
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
            Thread { io.github.tewnut.roadcapture.capture.Uploads.enqueuePending(ctx) }.start()   // pick up waiting sessions with the new settings
            onDone()
        }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (firstRun) "Xong, bắt đầu ghi" else "Xong", fontWeight = FontWeight.Bold)
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

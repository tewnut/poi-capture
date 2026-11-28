package io.github.tewnut.poicapture.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.ui.Hint
import io.github.tewnut.poicapture.ui.Palette
import io.github.tewnut.poicapture.ui.ScreenHeader

/** A settings page: the top bar with ← back, then a scrolling list (on the app's canvas, under the system bars). */
@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, subtitle: String? = null, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Canvas).safeDrawingPadding()) {
        ScreenHeader(title, onBack = onBack, subtitle = subtitle)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A white rounded card, the app's look for a group of settings. */
@Composable
internal fun WhiteBox(modifier: Modifier = Modifier, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Palette.Line), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(if (padding) Modifier.fillMaxWidth().padding(16.dp) else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (padding) 12.dp else 0.dp), content = content)
    }
}

/** One entry of a group of rows: icon, title, its current value. */
internal data class NavItem(val icon: String, val title: String, val value: String, val valueColor: Color = Palette.Muted,
                            val onClick: () -> Unit)

/** Rows in one card, divided by hairlines. */
@Composable
internal fun NavGroup(items: List<NavItem>) {
    WhiteBox(padding = false) {
        items.forEachIndexed { i, it ->
            NavRow(it)
            if (i < items.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp), color = Palette.Line)
        }
    }
}

/** A settings row: icon in a green circle, title, one line with the current value, a chevron. At least 64 dp high. */
@Composable
internal fun NavRow(item: NavItem) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = item.onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconCircle(item.icon)
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.value.isNotEmpty()) Text(item.value, fontSize = 13.sp, color = item.valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.Muted)
    }
}

@Composable
internal fun IconCircle(icon: String) {
    Box(Modifier.size(40.dp).background(Palette.GreenLight, CircleShape), contentAlignment = Alignment.Center) {
        Text(icon, fontSize = 18.sp)
    }
}

/** A card's head: icon, title, one line under it. */
@Composable
internal fun CardHead(icon: String, title: String, sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconCircle(icon)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.Ink)
            if (sub != null) Hint(sub)
        }
    }
}

/** A small upper-case label over a section of a page. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.padding(start = 4.dp, top = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp, color = Palette.Muted)
}

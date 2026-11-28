package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.R
import java.util.Locale

/**
 * How to record a Camera 360 ride (docs/camera360.md), per camera: GoPro MAX (records on its own, the sync QR lines
 * it up with the phone) and Ricoh Theta (the phone controls it). The texts are string arrays: a line starting with
 * "# " is a section, the others are steps, numbered within their section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Camera360Guide(initial: Int = 0, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(initial) }
    val tabs = listOf(stringResource(R.string.guide360_gopro), stringResource(R.string.guide360_theta))
    val lines = stringArrayResource(if (tab == 0) R.array.guide360_gopro_lines else R.array.guide360_theta_lines)
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.guide360_title), style = ScreenTitle.copy(fontSize = 24.sp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, t ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = Palette.GreenLight)) {
                        Text(t, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            var n = 0
            for (line in lines) {
                if (line.startsWith("# ")) {
                    n = 0
                    Text(line.removePrefix("# ").uppercase(Locale.getDefault()), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp, color = Palette.Green, modifier = Modifier.padding(top = 8.dp))
                } else {
                    n++
                    Step(n, line)
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) = Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
    Box(Modifier.size(26.dp).background(Palette.GreenLight, CircleShape), contentAlignment = Alignment.Center) {
        Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    }
    Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = Palette.Ink, modifier = Modifier.weight(1f).padding(top = 2.dp))
}

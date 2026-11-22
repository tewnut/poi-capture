package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.R

/**
 * What to photograph at a place, in order, with an example of each (photos from a real Place capture). Every kind
 * gives the agent something to read: the front shows the place, the sign its name and phone, the door its opening
 * hours and menu, the business plate its legal name, address and tax code.
 */
data class GuideStep(val image: Int, val title: Int, val text: Int)

val PLACE_GUIDE = listOf(
    GuideStep(R.drawable.guide_front, R.string.guide_front_title, R.string.guide_front_text),
    GuideStep(R.drawable.guide_sign, R.string.guide_sign_title, R.string.guide_sign_text),
    GuideStep(R.drawable.guide_info, R.string.guide_info_title, R.string.guide_info_text),
    GuideStep(R.drawable.guide_plate, R.string.guide_plate_title, R.string.guide_plate_text),
)

/**
 * The four kinds as a list, in the instructions (New session ⓘ). Not over the camera: the capture screen keeps only
 * the camera and its action buttons.
 */
@Composable
fun PlaceGuideList() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PLACE_GUIDE.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(s.image), stringResource(s.title), Modifier.size(96.dp, 72.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${i + 1}. " + stringResource(s.title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Ink)
                    Text(stringResource(s.text), color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

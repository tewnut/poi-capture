package io.github.tewnut.poicapture.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.core.BuildingQuery
import io.github.tewnut.poicapture.core.LatLon
import io.github.tewnut.poicapture.net.OverpassClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Setup, indoor: which building (or where the lookup stands: waiting for a position, searching, none here, offline),
 * how many levels, what is mapped; always with the ways out: search by name, pick on the map, no building.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildingCard(st: BuildingLookupState, onSearch: () -> Unit) {
    val b = st.building.takeIf { !st.none }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Hint(stringResource(R.string.setup_building_label))
        if (b != null) {
            Text(b.name ?: stringResource(R.string.setup_building_unnamed), fontWeight = FontWeight.Bold, fontSize = 20.sp,
                color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(
                b.levels?.let { stringResource(R.string.setup_building_levels, it) },
                b.underground?.let { stringResource(R.string.setup_building_underground, it) },
                b.features.count { it.kind == "shop" }.takeIf { it > 0 }?.let { stringResource(R.string.setup_building_shops, it) },
                b.addr["addr:street"]?.let { s -> listOfNotNull(b.addr["addr:housenumber"], s).joinToString(" ") },
            )
            if (facts.isNotEmpty()) Hint(facts.joinToString(" · "))
        } else if (st.none) {
            Text(stringResource(R.string.setup_building_walk_without), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Ink)
        }
        // the lookup's state, always said (a building already shown stays while a new lookup runs)
        val (line, color) = when {
            st.none -> null to Palette.Muted
            st.pickOnMap -> stringResource(R.string.setup_building_tap_now) to Palette.Blue
            st.status == LookupStatus.WAITING_FIX -> stringResource(R.string.setup_building_waiting_fix) to Palette.Amber
            st.status == LookupStatus.SEARCHING -> stringResource(R.string.setup_building_loading) to Palette.Muted
            st.status == LookupStatus.NONE_FOUND -> stringResource(R.string.setup_building_none_found) to Palette.Amber
            st.status == LookupStatus.OFFLINE -> stringResource(R.string.setup_building_offline) to Palette.Red
            else -> stringResource(R.string.setup_building_tap) to Palette.Blue
        }
        if (line != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.status == LookupStatus.SEARCHING || st.status == LookupStatus.WAITING_FIX)
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
            Text(line, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            if (st.status == LookupStatus.OFFLINE || st.status == LookupStatus.NONE_FOUND) TextButton(onClick = { st.retry() },
                contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(stringResource(R.string.setup_building_retry), color = Palette.Blue, fontWeight = FontWeight.Bold)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun Action(text: String, onClick: () -> Unit) = OutlinedButton(onClick = onClick, modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)) { Text(text, fontSize = 13.sp, color = Palette.Ink) }
            Action("🔎 " + stringResource(R.string.setup_building_search)) { onSearch() }
            Action("📍 " + stringResource(R.string.setup_building_pick_map)) { st.none = false; st.pickOnMap = true }
            if (!st.none) Action(stringResource(R.string.setup_building_none)) { st.chooseNone() }
        }
        Hint(stringResource(R.string.setup_indoor_hint))
    }
}

/** "Tìm theo tên": buildings, malls and markets by name within ~30 km, nearest first; a tap picks one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildingSearchSheet(overpassUrl: String, from: LatLon?, onPick: (BuildingQuery.Hit) -> Unit, onLog: (JSONObject) -> Unit,
                        onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<BuildingQuery.Hit>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() {
        val q = text.trim()
        if (q.length < 2 || from == null || busy) return
        busy = true; failed = false
        scope.launch {
            val trace = mutableListOf<OverpassClient.Attempt>()
            val t0 = SystemClock.elapsedRealtime()
            val line = JSONObject().put("type", "lookup").put("how", "name").put("text", q.take(60))
                .put("at", JSONArray().put(Math.round(from.lat * 1e4) / 1e4).put(Math.round(from.lon * 1e4) / 1e4))
            try {
                val body = OverpassClient.body(overpassUrl, BuildingQuery.nameSearchQuery(q, from), "name search", trace)
                val h = withContext(Dispatchers.Default) { BuildingQuery.parseSearch(body, from) }
                Log.i("QuetPho", "building: name search \"$q\": ${h.size} found")
                hits = h
                onLog(line.put("ms", SystemClock.elapsedRealtime() - t0).put("http", trace.lastOrNull()?.http ?: JSONObject.NULL)
                    .put("mirror", trace.lastOrNull()?.mirror ?: JSONObject.NULL).put("tries", trace.size).put("found", h.size)
                    .put("picked", JSONObject.NULL).put("error", JSONObject.NULL))
            } catch (e: Exception) {
                failed = true
                val why = trace.lastOrNull()?.error ?: e.message ?: e.javaClass.simpleName
                Log.i("QuetPho", "building: name search failed: $why")
                onLog(line.put("ms", SystemClock.elapsedRealtime() - t0).put("http", trace.lastOrNull()?.http ?: JSONObject.NULL)
                    .put("mirror", trace.lastOrNull()?.mirror ?: JSONObject.NULL).put("tries", trace.size).put("found", 0)
                    .put("picked", JSONObject.NULL).put("error", why.take(160)))
            } finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.setup_building_search), style = ScreenTitle.copy(fontSize = 24.sp))
            if (from == null) Text(stringResource(R.string.setup_building_search_no_position), color = Palette.Amber, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(stringResource(R.string.setup_building_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }))
                Button(onClick = { search() }, enabled = !busy && from != null && text.trim().length >= 2,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Black, contentColor = Palette.Lime)) {
                    Text(stringResource(R.string.setup_building_search_go), fontWeight = FontWeight.Bold)
                }
            }
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.setup_building_loading), color = Palette.Muted)
                }
                failed -> Text(stringResource(R.string.setup_building_offline), color = Palette.Red, fontSize = 14.sp)
                hits?.isEmpty() == true -> Text(stringResource(R.string.setup_building_search_nothing), color = Palette.Amber, fontSize = 14.sp)
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(hits ?: emptyList()) { h ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when (h.kind) { "mall" -> "🏬"; "marketplace" -> "🛒"; else -> "🏢" }, fontSize = 20.sp)
                        Column(Modifier.weight(1f)) {
                            Text(h.name, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h.building == null) Hint(stringResource(R.string.setup_building_search_point))
                        }
                        Text(if (h.distanceM < 1000) "${h.distanceM.toInt()} m" else String.format(Locale.ROOT, "%.1f km", h.distanceM / 1000),
                            color = Palette.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

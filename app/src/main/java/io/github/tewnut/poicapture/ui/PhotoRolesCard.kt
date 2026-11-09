package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.core.PhotoRole
import io.github.tewnut.poicapture.core.PhotoRoles

/**
 * "Loại ảnh khi chụp địa điểm": the photo kinds of POI mode, in shooting order. The contributor can reorder, remove,
 * add, pick the cover; when the team's server sends a config it is shown read-only.
 */
@Composable
fun PhotoRolesCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var roles by remember { mutableStateOf(Prefs.photoRoles(ctx)) }
    var adding by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    fun save(r: PhotoRoles) { roles = r; Prefs.savePhotoRoles(ctx, r) }
    val fromServer = roles.source == "server"

    Hint(stringResource(R.string.roles_intro))
    if (fromServer) Hint(stringResource(R.string.roles_from_server), color = Palette.Blue)

    for ((i, r) in roles.roles.withIndex()) {
        var menu by remember(r.id) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("${i + 1}.", color = Palette.Muted, modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text((if (r.id == roles.cover.id) "★ " else "") + roleLabel(roles, r) + (if (r.advance) "  ↻" else ""),
                    fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                val hint = roleHint(roles, r)
                if (hint.isNotBlank()) Hint(hint)
            }
            if (!fromServer) Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(menu, { menu = false }) {
                    if (i > 0) DropdownMenuItem({ Text(stringResource(R.string.roles_move_up)) }, { menu = false; save(roles.moved(r.id, -1)) })
                    if (i < roles.roles.size - 1) DropdownMenuItem({ Text(stringResource(R.string.roles_move_down)) }, { menu = false; save(roles.moved(r.id, 1)) })
                    if (r.id != roles.cover.id) DropdownMenuItem({ Text(stringResource(R.string.roles_make_cover)) }, {
                        menu = false
                        save(roles.copy(roles = roles.roles.map { it.copy(cover = it.id == r.id) }, source = "local"))
                    })
                    DropdownMenuItem({ Text(stringResource(if (r.advance) R.string.roles_advance_off else R.string.roles_advance_on)) }, {
                        menu = false
                        save(roles.copy(roles = roles.roles.map { if (it.id == r.id) it.copy(advance = !it.advance) else it }, source = "local"))
                    })
                    if (roles.roles.size > 1) DropdownMenuItem({ Text(stringResource(R.string.roles_remove), color = Palette.Red) }, { menu = false; save(roles.without(r.id)) })
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!fromServer) TextButton(onClick = { adding = true }) { Text(stringResource(R.string.roles_add)) }
        if (!fromServer && roles.source != "default") TextButton(onClick = { save(PhotoRoles.DEFAULT); msg = "" }) { Text(stringResource(R.string.roles_reset)) }
        if (Prefs.settings(ctx).apiUrl.isNotBlank()) TextButton(onClick = {
            msg = ctx.getString(R.string.roles_fetching)
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { Prefs.refreshConfig(ctx) }.getOrDefault(false) }
                roles = Prefs.photoRoles(ctx)
                msg = ctx.getString(if (ok) R.string.roles_updated else R.string.roles_no_server_config)
            }
        }) { Text(stringResource(R.string.roles_fetch)) }
    }
    if (msg.isNotEmpty()) Hint(msg, color = Palette.Blue)

    if (adding) {
        var label by remember { mutableStateOf("") }
        var hint by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.roles_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it.take(24) }, label = { Text(stringResource(R.string.roles_add_name)) }, singleLine = true)
                    OutlinedTextField(hint, { hint = it.take(80) }, label = { Text(stringResource(R.string.roles_add_hint)) })
                    Hint(stringResource(R.string.roles_add_code, PhotoRoles.slug(label.ifBlank { "…" }, roles.roles.map { it.id })))
                }
            },
            confirmButton = {
                TextButton(enabled = label.isNotBlank(), onClick = {
                    save(roles.with(PhotoRole(PhotoRoles.slug(label, roles.roles.map { it.id }), label.trim(), hint.trim())))
                    adding = false
                }) { Text(stringResource(R.string.roles_add_confirm)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.roles_cancel)) } },
        )
    }
}

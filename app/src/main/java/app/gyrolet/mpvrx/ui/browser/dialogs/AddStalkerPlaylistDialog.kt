/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.data.network.StalkerPortal
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun AddStalkerPlaylistDialog(onDismiss: () -> Unit, onImported: () -> Unit) {
  val context = LocalContext.current
  val repository = koinInject<PlaylistRepository>()
  val scope = rememberCoroutineScope()
  var name by remember { mutableStateOf("") }
  var portal by remember { mutableStateOf("") }
  var mac by remember { mutableStateOf("") }
  var agent by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  val ready = name.isNotBlank() && portal.isNotBlank() && mac.isNotBlank() && !busy
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text("Add MAG / Stalker portal") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(portal, { portal = it }, label = { Text("MAG portal URL") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mac, { mac = it }, label = { Text("MAC address") }, placeholder = { Text("00:1A:79:12:34:56") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(agent, { agent = it }, label = { Text("Custom user agent (optional)") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("User agent identifies the player to the portal. Leave blank to use the MAG default.")
        if (busy) CircularProgressIndicator()
      }
    },
    confirmButton = { TextButton(enabled = ready, onClick = {
      busy = true
      scope.launch {
        try { repository.createStalkerPlaylist(name, portal, mac.trim(), agent).onSuccess { onImported() }.onFailure { android.widget.Toast.makeText(context, playlistError(it), android.widget.Toast.LENGTH_LONG).show() } }
        finally { busy = false }
      }
    }) { Text("Connect and import") } },
    dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
  )
}

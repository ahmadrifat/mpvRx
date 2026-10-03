/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.browser.dialogs

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun EditPlaylistDialog(playlist: PlaylistEntity, onDismiss: () -> Unit, onSaved: () -> Unit) {
  val repository = koinInject<PlaylistRepository>()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var settings by remember { mutableStateOf<PlaylistRepository.Configuration?>(null) }
  var busy by remember { mutableStateOf(false) }
  LaunchedEffect(playlist.id) {
    repository.configuration(playlist.id).onSuccess { settings = it }.onFailure {
      Toast.makeText(context, playlistError(it), Toast.LENGTH_LONG).show(); onDismiss()
    }
  }
  AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Edit playlist") }, text = {
    val config = settings
    if (config == null) CircularProgressIndicator() else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(config.name, { settings = config.copy(name = it) }, label = { Text("Playlist name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
      if (playlist.isM3uPlaylist) {
        OutlinedTextField(config.url, { settings = config.copy(url = it) }, label = { Text(if (playlist.isXtreamPlaylist) "Server URL" else if (config.mag) "MAG portal URL" else "Playlist URL / file") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        if (playlist.isXtreamPlaylist) {
          OutlinedTextField(config.username, { settings = config.copy(username = it) }, label = { Text("Username") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(config.password, { settings = config.copy(password = it) }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("HLS stream format", Modifier.weight(1f))
            Switch(config.hls, { settings = config.copy(hls = it) }, enabled = !busy)
          }
        }
        if (config.mag) OutlinedTextField(config.mac, { settings = config.copy(mac = it) }, label = { Text("MAC address") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (!playlist.isXtreamPlaylist) OutlinedTextField(config.userAgent, { settings = config.copy(userAgent = it) }, label = { Text("User agent (optional)") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
      }
      if (busy) CircularProgressIndicator()
    }
  }, confirmButton = {
    TextButton(enabled = settings?.name?.isNotBlank() == true && !busy, onClick = {
      val config = settings ?: return@TextButton
      busy = true
      scope.launch {
        try { repository.saveConfiguration(playlist.id, config).onSuccess { onSaved() }.onFailure { Toast.makeText(context, playlistError(it, config.password), Toast.LENGTH_LONG).show() } }
        finally { busy = false }
      }
    }) { Text(if (busy) "Saving…" else "Save") }
  }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun PlaylistInformationDialog(playlist: PlaylistEntity, onDismiss: () -> Unit) {
  val repository = koinInject<PlaylistRepository>()
  val context = LocalContext.current
  var details by remember { mutableStateOf("Checking…") }
  var revision by remember { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf(true) }
  LaunchedEffect(playlist.id, revision) {
    busy = true
    repository.accountInformation(playlist.id).onSuccess { details = it }.onFailure {
      details = "Account information unavailable"
      Toast.makeText(context, playlistError(it), Toast.LENGTH_LONG).show()
    }
    busy = false
  }
  AlertDialog(onDismissRequest = onDismiss, title = { Text("Playlist information") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(details); if (busy) CircularProgressIndicator() } },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }, dismissButton = { TextButton(enabled = !busy, onClick = { revision++ }) { Text("Refresh") } })
}

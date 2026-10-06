/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.download.AppDownloadManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal class LocalFolderPicker : ActivityResultContracts.OpenDocumentTree() {
  override fun createIntent(context: android.content.Context, input: android.net.Uri?): Intent =
    super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
internal fun DownloadSettingsFields(options: DownloadExportOptions, onChange: (DownloadExportOptions) -> Unit, locked: Boolean = false, recording: Boolean = false) {
  val context = LocalContext.current
  val manager = koinInject<AppDownloadManager>()
  val scope = rememberCoroutineScope()
  var showThumbnail by remember { mutableStateOf(false) }
  var advanced by remember { mutableStateOf(false) }
  val folderPicker = rememberLauncherForActivityResult(LocalFolderPicker()) { uri ->
    if (uri != null) {
      val path = manager.locations.localFolder(uri)
      if (path == null) Toast.makeText(context, "Choose a writable local folder", Toast.LENGTH_SHORT).show()
      else {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        onChange(options.copy(directory = path))
      }
    }
  }
  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    uri?.let { scope.launch {
      try { onChange(options.copy(thumbnail = ExportFiles.thumbnail(context, it.toString()), thumbnailCustom = true)) }
      catch (error: kotlinx.coroutines.CancellationException) { throw error }
      catch (_: Exception) { Toast.makeText(context, "Could not read the selected image", Toast.LENGTH_SHORT).show() }
    } }
  }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    OutlinedTextField(value = options.fileName, onValueChange = { onChange(options.copy(fileName = it)) }, label = { Text("File name") }, singleLine = true, enabled = !locked, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
      Text("Save location: ${options.directory ?: "Default"}", maxLines = 2)
    }
    if (!locked && options.directory != null) TextButton(onClick = { onChange(options.copy(directory = null)) }) { Text("Use default location") }
    if (!locked && !recording) TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide author and thumbnail ▴" else "Author and thumbnail ▾") }
    if (!locked && !recording && advanced) {
      OutlinedTextField(value = options.author, onValueChange = { onChange(options.copy(author = it)) }, label = { Text("Author / Artist (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showThumbnail = !showThumbnail }, modifier = Modifier.weight(1f)) { Text("Thumbnail") }
        OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Choose image") }
      }
      if (showThumbnail) {
        options.thumbnail?.takeIf(String::isNotBlank)?.let { image -> app.gyrolet.mpvrx.presentation.components.RemoteImage(image, "Selected thumbnail", modifier = Modifier.fillMaxWidth().height(100.dp)) }
        OutlinedTextField(value = options.thumbnail.orEmpty(), onValueChange = { onChange(options.copy(thumbnail = it.takeIf(String::isNotBlank), thumbnailCustom = true)) }, label = { Text("Thumbnail URL or selected image") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
        Text("The thumbnail is kept in Downloads. Cover art is embedded in supported formats.", style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

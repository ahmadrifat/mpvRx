/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
  var thumbnailMenu by remember { mutableStateOf(false) }
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
  val imagePicker = rememberLauncherForActivityResult(object : ActivityResultContracts.GetContent() {
    override fun createIntent(context: android.content.Context, input: String) = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
  }) { uri ->
    uri?.let { scope.launch {
      try { onChange(options.copy(thumbnail = ExportFiles.thumbnail(context, it.toString()), thumbnailCustom = true, thumbnailMode = "storage", thumbnailSource = it.toString())) }
      catch (error: kotlinx.coroutines.CancellationException) { throw error }
      catch (_: Exception) { Toast.makeText(context, "Could not read the selected image", Toast.LENGTH_SHORT).show() }
    } }
  }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    app.gyrolet.mpvrx.presentation.components.EditableFileName(value = options.fileName, onValueChange = { onChange(options.copy(fileName = it)) }, label = "File name", enabled = !locked, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
      Text("Save location: ${options.directory ?: "Default"}", maxLines = 2)
    }
    if (!locked && options.directory != null) TextButton(onClick = { onChange(options.copy(directory = null)) }) { Text("Use default location") }
    if (!recording) {
      OutlinedTextField(value = options.author, onValueChange = { onChange(options.copy(author = it)) }, label = { Text("Author (optional)") }, enabled = !locked, modifier = Modifier.fillMaxWidth(), maxLines = 3)
      Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { thumbnailMenu = true }, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
          Text("Thumbnail: ${when (options.thumbnailMode) { "storage" -> "From storage"; "link" -> "External link"; else -> "From source" }} ▾")
        }
        DropdownMenu(expanded = thumbnailMenu, onDismissRequest = { thumbnailMenu = false }) {
          listOf("source" to "From source", "storage" to "From storage", "link" to "External link").forEach { (mode, label) ->
            DropdownMenuItem(text = { Text(label) }, onClick = {
              thumbnailMenu = false
              if (mode != options.thumbnailMode) onChange(options.copy(thumbnailMode = mode,
                thumbnail = if (mode == "source") options.sourceThumbnail else null,
                thumbnailCustom = mode != "source", thumbnailSource = null))
            })
          }
        }
      }
      if (options.thumbnailMode == "storage") {
        Box(Modifier.fillMaxWidth().clickable(enabled = !locked) { imagePicker.launch("image/*") }) {
          OutlinedTextField(value = options.thumbnailSource.orEmpty(), onValueChange = {}, enabled = false,
            label = { Text("Thumbnail source") }, placeholder = { Text("Select an image from storage") }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(disabledTextColor = MaterialTheme.colorScheme.onSurface,
              disabledBorderColor = MaterialTheme.colorScheme.outline, disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
              disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant),
            trailingIcon = { Text("Browse", modifier = Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary) }, maxLines = 3)
        }
      } else if (options.thumbnailMode == "link") {
        OutlinedTextField(value = options.thumbnail.orEmpty(), onValueChange = { onChange(options.copy(thumbnail = it.takeIf(String::isNotBlank), thumbnailCustom = true, thumbnailSource = it)) },
          label = { Text("Thumbnail source") }, placeholder = { Text("Image URL") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
      }
    }
  }
}

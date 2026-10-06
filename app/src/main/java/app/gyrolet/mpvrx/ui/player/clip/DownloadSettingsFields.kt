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
  val scope = rememberCoroutineScope()
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
    if (!recording) {
      OutlinedTextField(value = options.author, onValueChange = { onChange(options.copy(author = it)) }, label = { Text("Author (optional)") }, enabled = !locked, modifier = Modifier.fillMaxWidth(), maxLines = 3)
      val sourceMode = options.thumbnailMode == "source"
      val storageMode = options.thumbnailMode == "storage"
      OutlinedTextField(value = if (sourceMode) options.sourceThumbnail ?: options.thumbnail.orEmpty() else if (storageMode) options.thumbnailSource.orEmpty() else options.thumbnail.orEmpty(),
        onValueChange = { onChange(options.copy(thumbnail = it.takeIf(String::isNotBlank), thumbnailCustom = true, thumbnailSource = it)) },
        readOnly = options.thumbnailMode != "link", label = { Text("Thumbnail source") },
        placeholder = { Text(if (sourceMode) "No thumbnail found" else if (storageMode) "Selected image location" else "Image URL") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("source" to "Source", "storage" to "Storage", "link" to "Link").forEach { (mode, label) ->
          val selected = options.thumbnailMode == mode
          OutlinedButton(onClick = {
            when (mode) {
              "storage" -> imagePicker.launch("image/*")
              "source" -> onChange(options.copy(thumbnailMode = mode, thumbnail = options.sourceThumbnail, thumbnailCustom = false, thumbnailSource = null))
              "link" -> if (!selected) onChange(options.copy(thumbnailMode = mode, thumbnailCustom = true, thumbnailSource = options.thumbnail))
            }
          }, modifier = Modifier.weight(1f), border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
              contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary)) { Text(label) }
        }
      }
      options.thumbnail?.takeIf(String::isNotBlank)?.let {
        app.gyrolet.mpvrx.presentation.components.RemoteImage(it, "Thumbnail preview", modifier = Modifier.fillMaxWidth().height(100.dp))
      }
    }
    if (recording) DownloadDestinationField(options, onChange, locked)
  }
}

@Composable
internal fun DownloadDestinationField(options: DownloadExportOptions, onChange: (DownloadExportOptions) -> Unit, locked: Boolean = false) {
  val context = LocalContext.current
  val manager = koinInject<AppDownloadManager>()
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
  Box(Modifier.fillMaxWidth().clickable(enabled = !locked) { folderPicker.launch(null) }) {
    OutlinedTextField(value = options.directory ?: "Default", onValueChange = {}, enabled = false,
      label = { Text("Save directory") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
      colors = OutlinedTextFieldDefaults.colors(disabledTextColor = MaterialTheme.colorScheme.onSurface,
        disabledBorderColor = MaterialTheme.colorScheme.outline, disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant),
      trailingIcon = { Text("Browse", modifier = Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary) })
  }
  if (!locked && options.directory != null) TextButton(onClick = { onChange(options.copy(directory = null)) }) { Text("Use default location") }
}

/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.graphics.BitmapFactory
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gyrolet.mpvrx.domain.download.AppDownloadManager
import app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine
import app.gyrolet.mpvrx.ui.player.controls.components.panels.DraggablePanel
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.compose.koinInject
import java.io.File
import java.util.Locale

/** Browser-owned editor. Opening or saving it never opens, pauses or replaces the player. */
@Composable
internal fun NetworkDownloadDialog(prepared: PreparedNetworkDownload, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val engine = koinInject<YtdlpDownloadEngine>()
  val manager = koinInject<AppDownloadManager>()
  val scope = rememberCoroutineScope()
  var options by remember(prepared) { mutableStateOf(prepared.options) }
  var audio by remember { mutableStateOf(false) }
  var format by remember { mutableStateOf(AudioExportFormat.M4A) }
  var start by remember { mutableStateOf(0f) }
  var end by remember { mutableStateOf(prepared.duration?.toFloat()) }
  var crop by remember { mutableStateOf<ClipCrop?>(null) }
  var cropImage by remember { mutableStateOf<File?>(null) }
  var preparingCrop by remember { mutableStateOf(false) }
  val scroll = rememberScrollState()
  val recording by LiveRecording.state.collectAsState()
  val recordMode = prepared.recordable && (prepared.live != false || prepared.duration == null)
  var submitting by remember { mutableStateOf(false) }
  fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    if (recordMode) {
      val locked = recording != null
      val shownOptions = if (locked) options.copy(fileName = recording!!.name, directory = recording!!.directory) else options
      DraggablePanel(heightFraction = .8f, header = { Text("Record stream", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          DownloadSettingsFields(shownOptions, { options = it }, locked = locked, recording = true)
          Text("File format: MKV")
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = {
              try {
                if (locked) { LiveRecording.stop(); onDismiss() }
                else if (LiveRecording.start(context, prepared.item, options)) onDismiss()
              } catch (_: Exception) { message("Could not start recording. Check the source and save directory.") }
            }, enabled = options.fileName.isNotBlank() && recording?.stopping != true, modifier = Modifier.weight(1f)) {
              Text(if (locked) "Stop recording" else "Start recording")
            }
          }
        }
      }
    } else {
      val duration = prepared.duration!!.toFloat()
      val length = ((end ?: duration) - start).coerceAtLeast(0f)
      val milliseconds = (length * 1000).toLong()
      ClipEditorPanel(
        state = ClipPanelState(audioOnly = audio, audioFormat = format, startSeconds = start, endSeconds = end,
          durationSeconds = duration, crop = crop, canSave = length > .05f,
          clipDuration = String.format(Locale.US, "%02d:%02d:%02d.%03d", milliseconds / 3600000, milliseconds / 60000 % 60, milliseconds / 1000 % 60, milliseconds % 1000)),
        scrollState = scroll,
        tabs = {
          if (!prepared.recordable) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "Video", true to "Audio").forEach { (isAudio, label) ->
              FilledTonalButton(onClick = { if (audio != isAudio) { audio = isAudio; scope.launch { scroll.scrollTo(0) } } },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (audio == isAudio) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.weight(1f)) { Text(label) }
            }
          }
        },
        onFormatChange = { format = it }, settings = { DownloadSettingsFields(options, { options = it }) },
        destination = { DownloadDestinationField(options, { options = it }) },
        videoFormatControl = {
          var expanded by remember { mutableStateOf(false) }
          Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Video format ▾") }
            DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .5f).dp)) {
              prepared.formats.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                options = options.copy(formatSelector = choice.selector, sourceExtension = choice.extension); expanded = false
              }) }
            }
          }
        },
        outputDescription = if (preparingCrop) "Preparing crop preview…" else prepared.formats.firstOrNull { it.selector == options.formatSelector }?.label,
        saveEnabled = !submitting && !preparingCrop && options.fileName.isNotBlank(),
        onRangeChange = { a, b, _ -> start = a; end = b }, onStartTimeChange = { start = it }, onEndTimeChange = { end = it },
        onMarkStart = { start = 0f }, onMarkEnd = { end = duration },
        onCrop = {
          if (!preparingCrop) {
            preparingCrop = true
            scope.launch {
              val preview = File.createTempFile("crop-preview-", ".png", context.cacheDir)
              try {
                withTimeout(30_000) {
                  val streams = if (YtdlpManager.requiresYtdlp(prepared.item.originalUri)) engine.resolveForClip(prepared.item.originalUri, formatSelector = options.formatSelector) else null
                  val input = streams?.video ?: prepared.item.playableUri
                  val headers = streams?.headers ?: prepared.item.headers
                  val args = buildList {
                    addAll(listOf("-v", "error", "-y", "-rw_timeout", "12000000", "-ss", start.toString()))
                    if (FfmpegRuntime.isHls(input, prepared.item.mimeType)) addAll(FfmpegRuntime.remoteHlsOptions)
                    if (headers.isNotEmpty()) addAll(listOf("-headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }))
                    addAll(listOf("-i", input, "-frames:v", "1", "-vf", "scale='min(1280,iw)':-2", preview.path))
                  }
                  require(FfmpegRuntime.run(context, args).first == 0 && preview.length() > 0)
                  cropImage = preview
                }
              } catch (_: kotlinx.coroutines.TimeoutCancellationException) { preview.delete(); message("Crop preview took too long to load") }
              catch (e: kotlinx.coroutines.CancellationException) { preview.delete(); throw e }
              catch (_: Exception) { preview.delete(); message("Could not prepare the crop preview") }
              finally { preparingCrop = false }
            }
          }
        }, onCancel = onDismiss,
        onSave = {
          if (!submitting) {
            submitting = true
            try {
              val whole = start <= .001f && kotlin.math.abs((end ?: duration) - duration) <= .01f
              val configured = options.copy(fullMedia = whole, directory = options.directory ?: if (!audio) manager.locations.linksDir().path else null)
              if (!audio && crop == null && whole && !prepared.recordable) {
                engine.enqueue(prepared.item.originalUri, configured.fileName, File(configured.directory!!),
                  formatSelector = configured.formatSelector, posterUrl = configured.thumbnail, headers = prepared.item.headers, exportOptions = configured)
              } else {
                require(ClipExportManager.export(context, ClipRequest(prepared.item, start.toDouble(), (end ?: duration).toDouble(),
                  if (audio) null else crop, audio, format, configured.copy(fullMedia = whole && !prepared.recordable))))
              }
              message("Saving — see Downloads"); onDismiss()
            } catch (_: Exception) { submitting = false; message("Could not start saving. Check the range and save directory.") }
          }
        },
      )
    }
  }
  cropImage?.let { image ->
    DisposableEffect(image) { onDispose { image.delete() } }
    var selector by remember(image) { mutableStateOf<CropSelectionView?>(null) }
    val bitmap = remember(image) { BitmapFactory.decodeFile(image.path) }
    if (bitmap != null) Dialog(onDismissRequest = { cropImage = null }) {
      Surface(shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(12.dp)) {
          AndroidView(factory = { ctx ->
            FrameLayout(ctx).apply {
              addView(ImageView(ctx).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(-1, -1))
              val w = prepared.item.videoWidth.takeIf { it > 0 } ?: bitmap.width
              val h = prepared.item.videoHeight.takeIf { it > 0 } ?: bitmap.height
              selector = CropSelectionView(ctx, w, h, w, h, 0, crop, {})
              addView(selector, FrameLayout.LayoutParams(-1, -1))
            }
          }, modifier = Modifier.fillMaxWidth().height(300.dp))
          Row {
            TextButton(onClick = { cropImage = null }) { Text("Cancel") }
            TextButton(onClick = { crop = null; cropImage = null }) { Text("Full frame") }
            Button(onClick = { crop = selector?.currentCrop(); cropImage = null }) { Text("Done") }
          }
        }
      }
    }
  }
}

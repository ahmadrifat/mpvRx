/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.downloads

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.download.AppDownload
import app.gyrolet.mpvrx.domain.download.AppDownloadManager
import app.gyrolet.mpvrx.domain.download.AppDownloadStatus
import app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import java.util.Locale

@Serializable
object DownloadsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val downloadManager = koinInject<AppDownloadManager>()
    val ytdlpEngine = koinInject<YtdlpDownloadEngine>()
    val offline = app.gyrolet.mpvrx.domain.torrent.OfflineTorrents
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { offline.initialize(context) } }
    val torrentRecords by offline.downloads.collectAsState()
    val torrents = torrentRecords.filterNot { it.status.startsWith("Video deleted") }
    var pendingTorrentDelete by remember { mutableStateOf<app.gyrolet.mpvrx.domain.torrent.OfflineTorrents.Download?>(null) }

    val downloads by downloadManager.downloads.collectAsState()
    val ytdlpJobs by ytdlpEngine.jobs.collectAsState()
    val activeSnapshot by downloadManager.activeSnapshot.collectAsState()

    // Progress notifications need POST_NOTIFICATIONS on Android 13+.
    val notificationPermissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }

    val locationPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
        }
        val resolved = downloadManager.locations.setLocationFromTree(uri)
        if (resolved == null) {
          Toast.makeText(context, R.string.downloads_location_invalid, Toast.LENGTH_LONG).show()
        }
      }

    var pendingDelete by remember { mutableStateOf<AppDownload?>(null) }

    app.gyrolet.mpvrx.ui.player.clip.ClipJobs.initialize(context)
    val clips by app.gyrolet.mpvrx.ui.player.clip.ClipJobs.jobs.collectAsState()
    val activeTorrents = torrents.filterNot { it.complete }
    val completedTorrents = torrents.filter { it.complete }
    val activeClips = clips.filter { it.status != "Completed" }
    val completedClips = clips.filter { it.status == "Completed" }
    val activeDownloads = downloads.filter { !it.isCompleted }
    val completedDownloads = downloads.filter { it.isCompleted }
    val activeYtdlp = ytdlpJobs.filter { it.state != YtdlpDownloadEngine.JobState.SUCCESS }
    val completedYtdlp = ytdlpJobs.filter { it.state == YtdlpDownloadEngine.JobState.SUCCESS }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.downloads_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.popSafely() }) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item(key = "location") {
          DownloadLocationCard(
            path = downloadManager.locations.root().absolutePath,
            isCustom = downloadManager.locations.isUsingCustomLocation(),
            onPick = { locationPicker.launch(null) },
            onReset = { downloadManager.locations.clearCustomLocation() },
          )
        }

        if (downloads.isEmpty() && ytdlpJobs.isEmpty() && torrents.isEmpty() && clips.isEmpty()) {
          item(key = "empty") {
            EmptyState(
              icon = Icons.RoundedFilled.FileDownload,
              title = stringResource(R.string.downloads_empty),
              message = stringResource(R.string.downloads_empty_hint),
              modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            )
          }
        }

        if (activeDownloads.isNotEmpty() || activeYtdlp.isNotEmpty() || activeTorrents.isNotEmpty() || activeClips.isNotEmpty()) {
          item(key = "active_header") { SectionHeader(stringResource(R.string.downloads_active_section)) }
          items(activeTorrents, key = { "torrent_${it.id}" }) { torrent ->
            DownloadMediaRow(title = torrent.title, subtitle = "${torrent.status} · ${(torrent.progress * 100).toInt()}%", posterUrl = null, progress = torrent.progress, indeterminate = false) {
              if (torrent.status == "Downloading") IconButton(onClick = { offline.pause(torrent.id) }) { Icon(Icons.RoundedFilled.Pause, contentDescription = "Pause") }
              else IconButton(onClick = { offline.resume(context, torrent) }) { Icon(Icons.RoundedFilled.PlayArrow, contentDescription = "Resume") }
              IconButton(onClick = { pendingTorrentDelete = torrent }) { Icon(Icons.RoundedFilled.Close, contentDescription = "Cancel") }
            }
          }
          items(activeClips, key = { "clip_${it.id}" }) { clip ->
            DownloadMediaRow(title = clip.title, subtitle = clip.error ?: clip.status, posterUrl = null, isError = clip.status == "Failed", progress = clip.progress, indeterminate = clip.active && clip.progress == null) {
              if (clip.active) IconButton(onClick = { app.gyrolet.mpvrx.ui.player.clip.ClipExportManager.cancel() }) { Icon(Icons.RoundedFilled.Close, contentDescription = "Cancel") }
              else {
                IconButton(onClick = { app.gyrolet.mpvrx.ui.player.clip.ClipJobs.retry(context, clip) }) { Icon(Icons.RoundedFilled.Refresh, contentDescription = "Retry") }
                IconButton(onClick = { app.gyrolet.mpvrx.ui.player.clip.ClipJobs.remove(context, clip) }) { Icon(Icons.RoundedFilled.Close, contentDescription = "Remove") }
              }
            }
          }
          items(activeYtdlp, key = { "ytdlp_${it.id}" }) { job ->
            YtdlpJobRow(
              job = job,
              onCancel = { ytdlpEngine.cancel(job.id) },
              onRetry = { ytdlpEngine.retry(job.id) },
              onPause = { ytdlpEngine.pause(job.id) },
              onResume = { ytdlpEngine.resume(job.id) },
              onRemove = { ytdlpEngine.remove(job.id) },
            )
          }
          items(activeDownloads, key = { "dl_${it.id}" }) { download ->
            ActiveDownloadRow(
              download = download,
              speedBytesPerSec = activeSnapshot?.takeIf { it.id == download.id }?.speedBytesPerSec ?: 0L,
              onRetry = { downloadManager.retry(download.id) },
              onPause = { downloadManager.pause(download.id) },
              onResume = { downloadManager.resume(download.id) },
              onCancel = { downloadManager.remove(download, deleteFile = true) },
            )
          }
        }

        if (completedDownloads.isNotEmpty() || completedYtdlp.isNotEmpty() || completedTorrents.isNotEmpty() || completedClips.isNotEmpty()) {
          item(key = "completed_header") { SectionHeader(stringResource(R.string.downloads_completed_section)) }
          items(completedTorrents, key = { "torrent_done_${it.id}" }) { torrent ->
            CompletedRow(title = torrent.title, subtitle = File(torrent.path).name, posterUrl = null, mediaPath = torrent.path, playable = torrent.complete,
              onPlay = { MediaUtils.playFile(source = torrent.path, context = context, launchSource = "downloads", title = torrent.title) }, onDelete = { pendingTorrentDelete = torrent })
          }
          items(completedClips, key = { "clip_done_${it.id}" }) { clip ->
            CompletedRow(title = clip.title, subtitle = "${if (clip.audioOnly) "${clip.audioFormat.name} audio" else "Clipped"} · ${"%.3f".format(clip.start)}–${"%.3f".format(clip.end)} s", posterUrl = null, mediaPath = clip.output, playable = clip.output != null,
              onPlay = { clip.output?.let { MediaUtils.playFile(source = it, context = context, launchSource = "downloads", title = clip.title, isAudio = clip.audioOnly) } }, onDelete = { app.gyrolet.mpvrx.ui.player.clip.ClipJobs.remove(context, clip) })
          }
          items(completedYtdlp, key = { "ytdlp_done_${it.id}" }) { job ->
            CompletedRow(
              title = job.title,
              subtitle = job.outputFile?.let { File(it).name }.orEmpty(),
              posterUrl = job.posterUrl,
              mediaPath = job.outputFile,
              playable = job.outputFile?.let { File(it).isFile } == true,
              onPlay = {
                job.outputFile?.let { path ->
                  MediaUtils.playFile(source = path, context = context, launchSource = "downloads", title = job.title)
                }
              },
              onDelete = {
                ytdlpEngine.remove(job.id, deleteFiles = true)
              },
            )
          }
          items(completedDownloads, key = { "dl_done_${it.id}" }) { download ->
            CompletedRow(
              title = download.displayTitle,
              posterUrl = download.entity.posterUrl,
              mediaPath = download.file.absolutePath,
              subtitle = buildString {
                append(download.entity.fileName)
                if (download.entity.totalBytes > 0) {
                  append("  •  ")
                  append(formatBytes(download.entity.totalBytes))
                }
              },
              playable = download.isPlayable,
              onPlay = {
                MediaUtils.playFile(
                  source = download.file.absolutePath,
                  context = context,
                  launchSource = "downloads",
                  title = download.displayTitle,
                  subtitles = downloadManager.sidecarSubtitles(download).map { Uri.fromFile(it) },
                  posterUrl = download.entity.posterUrl,
                  isAudio = download.entity.isAudio,
                )
              },
              onDelete = { pendingDelete = download },
            )
          }
        }
      }
    }

    pendingTorrentDelete?.let { torrent ->
      LaunchedEffect(torrent.id) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { offline.remove(torrent, false) }
        pendingTorrentDelete = null
      }
    }
    pendingDelete?.let { download ->
      AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(R.string.downloads_delete_confirm_title)) },
        text = { Text(stringResource(R.string.downloads_delete_confirm_message, download.displayTitle)) },
        confirmButton = {
          TextButton(
            onClick = {
              downloadManager.remove(download, deleteFile = true)
              pendingDelete = null
            },
          ) { Text(stringResource(R.string.downloads_delete_file)) }
        },
        dismissButton = {
          Row {
            TextButton(
              onClick = {
                downloadManager.remove(download, deleteFile = false)
                pendingDelete = null
              },
            ) { Text(stringResource(R.string.downloads_remove_entry)) }
            TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.generic_cancel)) }
          }
        },
      )
    }
  }
}

@Composable
private fun SectionHeader(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
  )
}

@Composable
private fun DownloadLocationCard(
  path: String,
  isCustom: Boolean,
  onPick: () -> Unit,
  onReset: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        Icons.RoundedFilled.Folder,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Spacer(modifier = Modifier.width(10.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.downloads_location),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = if (isCustom) path else stringResource(R.string.downloads_location_default),
          style = MaterialTheme.typography.bodySmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (isCustom) {
        IconButton(onClick = onReset) {
          Icon(
            Icons.RoundedFilled.Close,
            contentDescription = stringResource(R.string.downloads_location_reset),
            modifier = Modifier.size(20.dp),
          )
        }
      }
      IconButton(onClick = onPick) {
        Icon(
          Icons.RoundedFilled.Edit,
          contentDescription = stringResource(R.string.downloads_location),
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}

@Composable
private fun DownloadThumbnail(posterUrl: String?, mediaPath: String?) {
  val context = LocalContext.current
  val repository = koinInject<ThumbnailRepository>()
  val density = LocalDensity.current
  val widthPx = with(density) { 88.dp.roundToPx() }
  val heightPx = (widthPx * 9f / 16f).toInt().coerceAtLeast(1)
  val localThumbnail by produceState<ImageBitmap?>(null, mediaPath, widthPx, heightPx) {
    val file = mediaPath?.let(::File)?.takeIf(File::isFile)
    val video = file?.let { MediaFileRepository.getVideosFromFiles(context, listOf(it)).firstOrNull() }
    value = video?.let { repository.getThumbnail(it, widthPx, heightPx)?.asImageBitmap() }
  }
  Box(
    modifier = Modifier.width(88.dp).aspectRatio(16f / 9f)
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.RoundedFilled.FileDownload,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(24.dp),
    )
    localThumbnail?.let { bitmap ->
      Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    }
    posterUrl?.takeIf(String::isNotBlank)?.let { url ->
      RemoteImage(
        url = url,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    }
  }
}

@Composable
private fun DownloadMediaRow(
  title: String,
  subtitle: String,
  posterUrl: String?,
  mediaPath: String? = null,
  isError: Boolean = false,
  progress: Float? = null,
  indeterminate: Boolean = false,
  actions: @Composable RowScope.() -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        DownloadThumbnail(posterUrl, mediaPath)
        Text(
          text = title,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = subtitle,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodySmall,
          color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = if (isError) 4 else 2,
          overflow = TextOverflow.Ellipsis,
        )
        actions()
      }
      if (indeterminate) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      } else if (progress != null) {
        LinearProgressIndicator(
          progress = { progress.coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun ActiveDownloadRow(
  download: AppDownload,
  speedBytesPerSec: Long,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onRetry: () -> Unit,
  onCancel: () -> Unit,
) {
  val status = download.status
  DownloadMediaRow(
    title = download.displayTitle,
    subtitle = downloadStatusLine(download, speedBytesPerSec),
    posterUrl = download.entity.posterUrl,
    isError = status == AppDownloadStatus.FAILED,
    progress = (download.entity.progress / 100f).takeIf { status == AppDownloadStatus.RUNNING },
    indeterminate = status == AppDownloadStatus.QUEUED ||
      status == AppDownloadStatus.RUNNING && download.entity.totalBytes <= 0,
  ) {
    if (download.isActive) IconButton(onClick = onPause) { Icon(Icons.RoundedFilled.Pause, contentDescription = "Pause") }
    if (status == AppDownloadStatus.PAUSED) IconButton(onClick = onResume) { Icon(Icons.RoundedFilled.PlayArrow, contentDescription = "Resume") }
    if (status == AppDownloadStatus.FAILED || status == AppDownloadStatus.CANCELLED) {
      IconButton(onClick = onRetry) {
        Icon(Icons.RoundedFilled.Refresh, contentDescription = stringResource(R.string.downloads_retry))
      }
    }
    IconButton(onClick = onCancel) {
      Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.downloads_cancel))
    }
  }
}

@Composable
private fun YtdlpJobRow(
  job: YtdlpDownloadEngine.Job,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onRemove: () -> Unit,
) {
  DownloadMediaRow(
    title = job.title,
    subtitle = ytdlpStatusLine(job),
    posterUrl = job.posterUrl,
    isError = job.state == YtdlpDownloadEngine.JobState.FAILED,
    progress = (job.progressPercent / 100f).takeIf { job.state == YtdlpDownloadEngine.JobState.RUNNING },
    indeterminate = job.state == YtdlpDownloadEngine.JobState.QUEUED ||
      job.state == YtdlpDownloadEngine.JobState.RUNNING && (job.progressPercent <= 0f || job.detail.startsWith("Finalizing")),
  ) {
    if (job.isActive) IconButton(onClick = onPause) { Icon(Icons.RoundedFilled.Pause, contentDescription = "Pause") }
    if (job.state == YtdlpDownloadEngine.JobState.PAUSED) IconButton(onClick = onResume) { Icon(Icons.RoundedFilled.PlayArrow, contentDescription = "Resume") }
    when (job.state) {
      YtdlpDownloadEngine.JobState.FAILED, YtdlpDownloadEngine.JobState.CANCELLED -> {
        IconButton(onClick = onRetry) {
          Icon(Icons.RoundedFilled.Refresh, contentDescription = stringResource(R.string.downloads_retry))
        }
        IconButton(onClick = onRemove) {
          Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.downloads_remove_entry))
        }
      }
      else -> {
        IconButton(onClick = onCancel) {
          Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.downloads_cancel))
        }
      }
    }
  }
}

@Composable
private fun CompletedRow(
  title: String,
  subtitle: String,
  posterUrl: String?,
  mediaPath: String?,
  playable: Boolean,
  onPlay: () -> Unit,
  onDelete: () -> Unit,
) {
  DownloadMediaRow(
    title = title,
    subtitle = subtitle,
    posterUrl = posterUrl,
    mediaPath = mediaPath,
  ) {
    IconButton(onClick = onPlay, enabled = playable) {
      Icon(
        Icons.RoundedFilled.PlayArrow,
        contentDescription = stringResource(R.string.downloads_play),
        tint = if (playable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
      )
    }
    IconButton(onClick = onDelete) {
      Icon(Icons.RoundedFilled.Delete, contentDescription = stringResource(R.string.downloads_delete_file))
    }
  }
}

@Composable
private fun downloadStatusLine(
  download: AppDownload,
  speedBytesPerSec: Long = 0L,
): String {
  val entity = download.entity
  return when (download.status) {
    AppDownloadStatus.PAUSED -> "Paused"
    AppDownloadStatus.QUEUED -> stringResource(R.string.downloads_preparing)
    AppDownloadStatus.FAILED ->
      stringResource(R.string.downloads_failed) +
        entity.failureReason?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    AppDownloadStatus.CANCELLED -> stringResource(R.string.downloads_cancelled)
    AppDownloadStatus.SUCCESS -> stringResource(R.string.downloads_downloaded)
    AppDownloadStatus.RUNNING ->
      buildString {
        append("${entity.progress}%")
        if (entity.totalBytes > 0) {
          val downloadedBytes = entity.totalBytes * entity.progress / 100
          append("  •  ${formatBytes(downloadedBytes)}/${formatBytes(entity.totalBytes)}")
        }
        if (speedBytesPerSec > 0) append("  •  ${formatBytes(speedBytesPerSec)}/s")
      }
  }
}

@Composable
private fun ytdlpStatusLine(job: YtdlpDownloadEngine.Job): String =
  when (job.state) {
    YtdlpDownloadEngine.JobState.PAUSED -> "Paused"
    YtdlpDownloadEngine.JobState.QUEUED -> stringResource(R.string.downloads_preparing)
    YtdlpDownloadEngine.JobState.RUNNING ->
      if (job.detail.startsWith("Finalizing")) job.detail else "${"%.1f".format(Locale.US, job.progressPercent)}% ${job.detail}".trim()
    YtdlpDownloadEngine.JobState.FAILED ->
      stringResource(R.string.downloads_failed) + job.error?.let { ": $it" }.orEmpty()
    YtdlpDownloadEngine.JobState.CANCELLED -> stringResource(R.string.downloads_cancelled)
    YtdlpDownloadEngine.JobState.SUCCESS -> stringResource(R.string.downloads_downloaded)
  }

private fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024) return "%.0f KB".format(Locale.US, kb)
  val mb = kb / 1024.0
  if (mb < 1024) return "%.1f MB".format(Locale.US, mb)
  return "%.2f GB".format(Locale.US, mb / 1024.0)
}

/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
internal object YtdlpMediaMerger {
  suspend fun merge(
    context: Context,
    candidates: List<File>,
    outputFile: File,
  ): File {
    val inputs = withContext(Dispatchers.IO) { findInputs(candidates) }
    val stagingFile = File(outputFile.parentFile, "${outputFile.name}.merging")

    try {
      val result = app.gyrolet.mpvrx.ui.player.clip.FfmpegRuntime.run(context, listOf(
        "-hide_banner", "-nostdin", "-y", "-i", inputs.video.absolutePath, "-i", inputs.audio.absolutePath,
        "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-f", "mp4", stagingFile.absolutePath,
      ))
      check(result.first == 0) { "Could not merge audio and video: " + result.second.lineSequence().filter { it.isNotBlank() }.toList().takeLast(2).joinToString(" ").take(240) }
      return withContext(Dispatchers.IO) { publish(stagingFile, outputFile) }
    } finally { stagingFile.delete() }
  }

  private fun findInputs(candidates: List<File>): MergeInputs {
    val inspected =
      candidates
        .asSequence()
        .filter { it.isFile && it.length() > 0L }
        .mapNotNull(::inspect)
        .toList()
    val video = inspected.firstOrNull { it.hasVideo && !it.hasAudio } ?: inspected.firstOrNull { it.hasVideo }
      ?: throw IllegalStateException("Downloaded video stream was not found")
    val audio =
      inspected.firstOrNull { it.file != video.file && it.hasAudio && !it.hasVideo }
        ?: inspected.firstOrNull { it.file != video.file && it.hasAudio }
        ?: throw IllegalStateException("Downloaded audio stream was not found")
    return MergeInputs(video.file, audio.file)
  }

  private fun inspect(file: File): InspectedFile? =
    runCatching {
      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(file.absolutePath)
        var hasVideo = false
        var hasAudio = false
        repeat(extractor.trackCount) { index ->
          when {
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true -> hasVideo = true
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true -> hasAudio = true
          }
        }
        InspectedFile(file, hasVideo, hasAudio).takeIf { it.hasVideo || it.hasAudio }
      } finally {
        extractor.release()
      }
    }.getOrNull()

  private fun publish(
    stagingFile: File,
    outputFile: File,
  ): File {
    check(stagingFile.isFile && stagingFile.length() > 0L) { "Media merge produced no output" }
    outputFile.parentFile?.mkdirs()
    try {
      Files.move(
        stagingFile.toPath(),
        outputFile.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: IOException) {
      Files.move(stagingFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    check(outputFile.isFile && outputFile.length() > 0L) { "Could not finalize merged download" }
    return outputFile
  }

  private data class InspectedFile(
    val file: File,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
  )

  private data class MergeInputs(
    val video: File,
    val audio: File,
  )
}

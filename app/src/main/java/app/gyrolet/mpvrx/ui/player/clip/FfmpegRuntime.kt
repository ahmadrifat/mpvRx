/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** Executables stay in nativeLibraryDir, as required by Android's executable-file policy. */
object FfmpegRuntime {
  @Synchronized fun libraries(context: Context): File {
    val archive = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.zip.so")
    require(archive.isFile) { "The FFmpeg runtime is missing for this device" }
    val root = File(context.noBackupFilesDir, "clip-ffmpeg-${archive.length()}")
    if (!File(root, ".ready").isFile) {
      root.mkdirs()
      ZipFile(archive).use { zip ->
        zip.entries().asSequence().forEach { entry ->
          val target = File(root, entry.name).canonicalFile
          require(target.path.startsWith(root.canonicalPath + File.separator))
          if (entry.isDirectory) target.mkdirs() else {
            target.parentFile!!.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
          }
        }
      }
      // ZIP contains Unix symbolic links. Resolve these to copies rather than loading link text.
      repeat(8) {
        root.walkTopDown().filter { it.isFile && it.length() in 1..512 && ".so" in it.name }.forEach { link ->
          val value = runCatching { link.readText().trim() }.getOrDefault("")
          if (value.matches(Regex("[A-Za-z0-9_./+-]+"))) {
            val target = File(link.parentFile, value).canonicalFile
            if (target.path.startsWith(root.canonicalPath + File.separator) && target.isFile && target.length() > 512) target.copyTo(link, overwrite = true)
          }
        }
      }
      File(root, ".ready").writeText("1")
    }
    return File(root, "usr/lib")
  }

  fun executable(context: Context, probe: Boolean = false) = File(context.applicationInfo.nativeLibraryDir, if (probe) "libffprobe.so" else "libffmpeg.so").absolutePath

  @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
  suspend fun run(context: Context, args: List<String>, probe: Boolean = false, onLine: (String) -> Unit = {}): Pair<Int, String> = withContext(Dispatchers.IO) {
    val builder = ProcessBuilder(listOf(executable(context, probe)) + args).redirectErrorStream(true)
    builder.environment()["LD_LIBRARY_PATH"] = libraries(context).absolutePath + ":" + context.applicationInfo.nativeLibraryDir
    val coroutineContext = currentCoroutineContext()
    val process = builder.start()
    val cancellation = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) process.destroyForcibly() }
    val output = StringBuilder()
    try {
      process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
        coroutineContext.ensureActive()
        if (output.length < 2_000_000) output.appendLine(line)
        onLine(line)
      } }
      process.waitFor() to output.toString()
    } finally { cancellation?.dispose(); if (process.isAlive) process.destroyForcibly() }
  }
}

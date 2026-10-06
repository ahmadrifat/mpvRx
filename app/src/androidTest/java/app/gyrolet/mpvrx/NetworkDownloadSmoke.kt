package app.gyrolet.mpvrx

import android.app.Instrumentation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import app.gyrolet.mpvrx.database.repository.NetworkStreamEntryRepository
import app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.clip.NetworkDownloadPreparation
import app.gyrolet.mpvrx.ui.player.clip.FfmpegRuntime
import kotlinx.coroutines.runBlocking
import java.io.File
import org.koin.core.context.GlobalContext

/** Tests the browser flow and real demuxer evidence without credential-bearing public fixtures. */
internal object NetworkDownloadSmoke {
  fun run(test: Instrumentation, metadataOnly: Boolean = false): String {
    val context = test.targetContext
    app.gyrolet.mpvrx.preferences.BrowserPreferences(app.gyrolet.mpvrx.preferences.preference.AndroidPreferenceStore(context), context).onboardingCompleted.set(true)
    app.gyrolet.mpvrx.preferences.AppearancePreferences(app.gyrolet.mpvrx.preferences.preference.AndroidPreferenceStore(context)).showNetworkTab.set(true)
    var activity: android.app.Activity? = if (metadataOnly) null else test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val engine = if (!metadataOnly) GlobalContext.get().get<YtdlpDownloadEngine>() else YtdlpDownloadEngine(context, app.gyrolet.mpvrx.preferences.YtdlPreferences(app.gyrolet.mpvrx.preferences.preference.AndroidPreferenceStore(context)))
    var repository: NetworkStreamEntryRepository? = null
    val video = File(context.cacheDir, "clip-smoke/preflight-video.mp4")
    if (!video.exists()) runBlocking {
      video.parentFile!!.mkdirs()
      check(FfmpegRuntime.run(context, listOf("-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "5", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-movflags", "+faststart", video.path)).first == 0)
    }
    val ts = File(context.cacheDir, "clip-smoke/preflight.ts")
    runBlocking { check(FfmpegRuntime.run(context, listOf("-v", "error", "-y", "-i", video.path, "-t", "5", "-c", "copy", "-f", "mpegts", ts.path)).first == 0) }
    val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
      override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/play/opaque" -> newFixedLengthResponse(Response.Status.OK, "video/mp2t", ts.inputStream(), ts.length())
        "/live.m3u8" -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\n/play/opaque\n")
        "/vod.m3u8" -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\n/play/opaque\n#EXT-X-ENDLIST\n")
        "/expired" -> newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/html", "Expired")
        else -> { Thread.sleep(1500); newFixedLengthResponse(Response.Status.OK, "video/mp4", video.inputStream(), video.length()) }
      }
    }
    server.start()
    val base = "http://127.0.0.1:${server.listeningPort}"
    val source = "$base/video.mp4"
    val original = PlaybackSession.state.value
    fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    fun snapshot(): List<AccessibilityNodeInfo> { test.uiAutomation.clearCache(); return nodes(test.uiAutomation.rootInActiveWindow) }
    fun waitLabel(label: String): AccessibilityNodeInfo {
      val deadline = android.os.SystemClock.elapsedRealtime() + 35_000
      while (true) {
        snapshot().firstOrNull { it.text?.toString()?.lines()?.contains(label) == true || it.hintText?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
        check(android.os.SystemClock.elapsedRealtime() < deadline) { "Missing expected browser control: $label" }
        Thread.sleep(100)
      }
    }
    fun click(node: AccessibilityNodeInfo) {
      var target: AccessibilityNodeInfo? = node
      while (target != null && !target.isClickable) target = target.parent
      check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    try {
      runBlocking {
        val opaque = NetworkDownloadPreparation.prepare(context, PlaybackItem.fromUri("$base/play/opaque", title = "Opaque TS"), engine)
        check(opaque.recordable && opaque.duration == null && opaque.live == null)
        val live = NetworkDownloadPreparation.prepare(context, PlaybackItem.fromUri("$base/live.m3u8"), engine)
        check(live.recordable && live.live == true && live.duration == null)
        val finite = NetworkDownloadPreparation.prepare(context, PlaybackItem.fromUri("$base/vod.m3u8"), engine)
        check(finite.recordable && finite.live == false && finite.duration == 5.0)
        check(runCatching { NetworkDownloadPreparation.prepare(context, PlaybackItem.fromUri("$base/expired"), engine) }.isFailure)
        val file = NetworkDownloadPreparation.prepare(context, PlaybackItem.fromUri(source, title = "Network popup fixture"), engine)
        check(!file.recordable && file.duration != null && file.item.videoWidth > 0)
        val privateSource = File(context.getExternalFilesDir(null), "stream-diagnostic-url.txt")
        if (privateSource.exists()) {
          val provider = NetworkDownloadPreparation.prepare(context, PlaybackItem.fromUri(privateSource.readText().trim()), engine)
          check(provider.recordable && provider.duration == null)
        }
      }
      test.sendStatus(0, android.os.Bundle().apply { putString("metadata", "PASS: opaque TS, live/finite HLS and rejected source") })
      if (metadataOnly) return "PASS: Android metadata preflight, opaque TS, live/finite HLS and private provider sample"
      repository = GlobalContext.get().get<NetworkStreamEntryRepository>()
      runBlocking { repository!!.saveNormalEntry(source, "Network popup fixture") }
      click(waitLabel("Network"))
      waitLabel("Network popup fixture")
      val title = waitLabel("Network popup fixture")
      val row = title.parent?.parent ?: error("Missing network row")
      click(nodes(row).first { it.contentDescription?.toString() == context.getString(R.string.downloads_download) })
      waitLabel("Loading media information")
      waitLabel("File name"); waitLabel("Video"); waitLabel("Audio")
      check(PlaybackSession.state.value.generation == original.generation && PlaybackSession.state.value.currentItem == original.currentItem) { "Browser download modified playback" }
      click(waitLabel("Audio"))
      repeat(2) {
        snapshot().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        Thread.sleep(300)
      }
      waitLabel("M4A"); waitLabel("MP3"); waitLabel("WAV"); waitLabel("AAC")
      val screenshot = test.uiAutomation.takeScreenshot()
      File(context.getExternalFilesDir(null), "network-download-preview.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
      screenshot.recycle()
      // Private device fixture, populated only for a provider diagnostic; never committed or logged.
      val privateSource = File(context.getExternalFilesDir(null), "stream-diagnostic-url.txt")
      if (privateSource.exists()) {
        val url = privateSource.readText().trim()
        val player = test.startActivitySync(Intent(context, app.gyrolet.mpvrx.ui.player.PlayerActivity::class.java)
          .setAction(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(url), "video/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (PlaybackSession.state.value.phase != app.gyrolet.mpvrx.ui.player.PlaybackPhase.READY) {
          check(android.os.SystemClock.elapsedRealtime() < deadline) { "Private diagnostic stream did not become ready" }; Thread.sleep(100)
        }
        check(PlaybackSession.isLiveForDownload()) { "Extensionless provider stream did not expose recording; demuxer=${PlaybackSession.getPropertyString("file-format")}" }
        test.runOnMainSync { player.finish() }
        privateSource.delete()
      }
      return "PASS: opaque MPEG-TS, live/finite HLS, rejected source, row spinner and standalone Video/Audio editor leave playback unchanged"
    } catch (error: Throwable) {
      val screenshot = test.uiAutomation.takeScreenshot()
      File(context.getExternalFilesDir(null), "network-popup-failure.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
      screenshot.recycle()
      throw error
    } finally {
      server.stop()
      runBlocking { repository?.delete("normal:$source") }
      File(context.getExternalFilesDir(null), "stream-diagnostic-url.txt").delete()
      test.runOnMainSync { activity?.finish() }
    }
  }
}

/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.app.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.gyrolet.mpvrx.domain.download.DownloadNavigation
import kotlinx.coroutines.*

class RecordingService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("recordings", "Live recordings", NotificationManager.IMPORTANCE_LOW))
    fun notification(state: LiveRecording.State?) : Notification {
      val stop = PendingIntent.getService(this, 4204, Intent(this, RecordingService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val elapsed = state?.let { (System.currentTimeMillis() - it.started) / 1000 } ?: 0
      return NotificationCompat.Builder(this, "recordings").setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(state?.name ?: "Live recording").setContentText(if (state?.stopping == true) "Finishing recording" else "Recording · ${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}")
        .setContentIntent(DownloadNavigation.pendingIntent(this)).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(android.R.drawable.ic_media_pause, "Stop recording", stop).build()
    }
    ServiceCompat.startForeground(this, 4204, notification(LiveRecording.state.value), if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    scope.launch {
      while (true) {
        val state = LiveRecording.state.value
        if (state == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); break }
        manager.notify(4204, notification(state)); delay(1000)
      }
    }
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent?.action == "stop") LiveRecording.stop(); return START_NOT_STICKY }
  override fun onTimeout(startId: Int, fgsType: Int) { LiveRecording.stop(); stopSelf() }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
  companion object { fun start(context: Context) { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)) } }
}

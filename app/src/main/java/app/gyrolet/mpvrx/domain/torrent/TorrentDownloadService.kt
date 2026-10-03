/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.torrent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class TorrentDownloadService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("torrent_downloads", "Torrent downloads", NotificationManager.IMPORTANCE_LOW))
    val notification = NotificationCompat.Builder(this, "torrent_downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Keeping torrent videos for offline viewing").setOngoing(true).build()
    ServiceCompat.startForeground(this, 4201, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    scope.launch {
      OfflineTorrents.downloads.collect { downloads ->
        val active = downloads.filter { it.status == "Downloading" && !it.complete }
        if (active.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        else {
          val current = active.first()
          manager.notify(4201, NotificationCompat.Builder(this@TorrentDownloadService, "torrent_downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(current.title).setContentText("Downloading for offline viewing (${active.size} active)").setProgress(100, (current.progress * 100).toInt(), false).setOngoing(true).setOnlyAlertOnce(true).build())
        }
      }
    }
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
  override fun onTimeout(startId: Int, fgsType: Int) { OfflineTorrents.pauseBackground(); stopSelf() }
  override fun onDestroy() { scope.cancel(); OfflineTorrents.pauseBackground(); super.onDestroy() }
  companion object { fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, TorrentDownloadService::class.java)) } }
}

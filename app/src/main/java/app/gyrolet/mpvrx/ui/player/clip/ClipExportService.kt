/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.gyrolet.mpvrx.domain.download.DownloadNavigation
import kotlinx.coroutines.*

class ClipExportService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("clips", "Clipping and audio downloads", NotificationManager.IMPORTANCE_LOW))
    fun notification(job: ClipJobs.Job?) = NotificationCompat.Builder(this, "clips").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(job?.title ?: "Clipping and audio downloads").setContentText(job?.status ?: "Preparing").setContentIntent(DownloadNavigation.pendingIntent(this)).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, ((job?.progress ?: 0f) * 100).toInt(), job?.progress == null).build()
    ServiceCompat.startForeground(this, 4202, notification(null), if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    scope.launch { ClipJobs.jobs.collect { jobs ->
      val active = jobs.lastOrNull { it.active }
      if (active == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } else manager.notify(4202, notification(active))
    } }
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
  override fun onTimeout(startId: Int, fgsType: Int) { ClipExportManager.cancel(); stopSelf() }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
  companion object { fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ClipExportService::class.java)) }
}

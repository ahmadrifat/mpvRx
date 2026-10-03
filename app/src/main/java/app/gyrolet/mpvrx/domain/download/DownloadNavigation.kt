/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.gyrolet.mpvrx.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadNavigation {
  const val ACTION = "app.gyrolet.mpvrx.OPEN_DOWNLOADS"
  private val counter = MutableStateFlow(0L)
  val requests = counter.asStateFlow()
  private var pending = false
  @Synchronized fun accept(intent: Intent?) {
    if (intent?.action == ACTION) { pending = true; counter.value += 1 }
  }
  @Synchronized fun consume(): Boolean = pending.also { pending = false }
  fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 901, Intent(context, MainActivity::class.java).setAction(ACTION)
      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
}

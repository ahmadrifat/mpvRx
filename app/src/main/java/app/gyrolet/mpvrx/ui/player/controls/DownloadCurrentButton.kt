/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.gyrolet.mpvrx.domain.download.LinkDownloadCoordinator
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.controls.components.ControlsButton
import org.koin.compose.koinInject

@Composable
fun DownloadCurrentButton() {
  val context = LocalContext.current
  val coordinator = koinInject<LinkDownloadCoordinator>()
  val state by PlaybackSession.state.collectAsState()
  val item = state.currentItem ?: return
  val source = item.originalUri
  val torrents by app.gyrolet.mpvrx.domain.torrent.OfflineTorrents.downloads.collectAsState()
  val torrent = torrents.firstOrNull { it.source == source && it.index == item.torrentFileIndex }
  if (torrent?.complete == true) {
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.CircleShape) {
      androidx.compose.material3.Text("Downloaded", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = androidx.compose.ui.Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
  }
  if (!source.startsWith("https://", true) && !source.startsWith("http://", true)) return
  ControlsButton(
    icon = Icons.RoundedFilled.Download,
    title = "Download video",
    onClick = {
      val route = coordinator.enqueue(source, item.title)
      Toast.makeText(context, if (route == LinkDownloadCoordinator.Route.UNSUPPORTED) "This source cannot be downloaded" else "Download queued — see Downloads", Toast.LENGTH_SHORT).show()
    },
  )
}

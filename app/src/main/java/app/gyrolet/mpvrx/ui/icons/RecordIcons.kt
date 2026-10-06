/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The caller supplies exactly the same theme tint as other player controls. */
object RecordIcons {
  val Record = AppIcon(vector(false))
  val Stop = AppIcon(vector(true))
  private fun vector(stop: Boolean): ImageVector = ImageVector.Builder("Record${if (stop) "Stop" else "Start"}", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
      if (stop) {
        moveTo(2f, 8f); lineTo(9f, 8f); lineTo(9f, 15f); lineTo(2f, 15f); close()
      } else {
        moveTo(5.5f, 8f); curveTo(10.2f, 8f, 10.2f, 15f, 5.5f, 15f); curveTo(.8f, 15f, .8f, 8f, 5.5f, 8f); close()
      }
      moveTo(12f, 5f); lineTo(17.5f, 5f); curveTo(23.5f, 5f, 24f, 13f, 19f, 14f)
      lineTo(23f, 19f); lineTo(19.8f, 19f); lineTo(16.1f, 14.3f); lineTo(14.7f, 14.3f)
      lineTo(14.7f, 19f); lineTo(12f, 19f); close()
      moveTo(14.7f, 7.7f); lineTo(14.7f, 11.7f); lineTo(17.5f, 11.7f)
      curveTo(20.8f, 11.7f, 20.8f, 7.7f, 17.5f, 7.7f); close()
    }
  }.build()
}

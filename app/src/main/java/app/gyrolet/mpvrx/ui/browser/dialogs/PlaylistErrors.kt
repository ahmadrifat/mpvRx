/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.browser.dialogs

fun playlistError(error: Throwable, password: String = ""): String {
  var message = error.message?.takeIf(String::isNotBlank) ?: "Could not load this playlist"
  if (password.isNotBlank()) message = message.replace(password, "•••")
  return message.replace(Regex("https?://[^\\s]+"), "[server]").take(240)
}

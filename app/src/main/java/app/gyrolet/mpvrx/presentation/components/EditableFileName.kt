/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/** Wrap long names and retain selection/composition across parent recompositions. */
@Composable
fun EditableFileName(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
  var field by remember { mutableStateOf(TextFieldValue(value)) }
  if (field.text != value) field = field.copy(text = value, selection = TextRange(field.selection.start.coerceAtMost(value.length), field.selection.end.coerceAtMost(value.length)))
  val focus = LocalFocusManager.current
  OutlinedTextField(value = field, onValueChange = {
    val text = it.text.replace('\n', ' ').replace('\r', ' ')
    field = it.copy(text = text)
    onValueChange(text)
  }, label = { Text(label) }, enabled = enabled, modifier = modifier, maxLines = 4,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
}

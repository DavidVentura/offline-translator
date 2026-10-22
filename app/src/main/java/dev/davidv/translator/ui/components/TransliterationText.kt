/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator.ui.components

import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView

private const val TRANSLITERATION_SIZE_RATIO = 0.75f

@Composable
fun TransliterationText(
  text: String,
  mainStyle: TextStyle,
  modifier: Modifier = Modifier,
) {
  val textSize = mainStyle.fontSize.value * TRANSLITERATION_SIZE_RATIO
  val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
  AndroidView(
    factory = { context ->
      TextView(context).apply { setTextIsSelectable(true) }
    },
    update = { textView ->
      textView.text = text
      textView.textSize = textSize
      textView.setTextColor(textColor)
    },
    modifier = modifier,
  )
}

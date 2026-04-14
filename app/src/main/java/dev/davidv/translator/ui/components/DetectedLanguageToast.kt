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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davidv.translator.DownloadState
import dev.davidv.translator.LangAvailability
import dev.davidv.translator.Language
import dev.davidv.translator.ui.theme.TranslatorTheme

@Composable
fun DetectedLanguageToast(
  detectedLanguage: Language,
  availableLanguages: Map<Language, LangAvailability>,
  onSwitchClick: () -> Unit,
  onEvent: (LanguageEvent) -> Unit,
  modifier: Modifier = Modifier,
  downloadStates: Map<Language, DownloadState> = emptyMap(),
) {
  val isLanguageAvailable = availableLanguages[detectedLanguage]?.translatorFiles == true

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .semantics { contentDescription = "Detected language toast" },
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      modifier = Modifier.weight(1f),
    ) {
      Text(
        text = if (isLanguageAvailable) "Translate from" else "Missing language",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text = detectedLanguage.displayName,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    if (isLanguageAvailable) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = "Switch to detected language",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier =
          Modifier
            .clickable { onSwitchClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
      )
    } else {
      LanguageDownloadButton(
        language = detectedLanguage,
        downloadState = downloadStates[detectedLanguage],
        isLanguageAvailable = availableLanguages[detectedLanguage]!!.translatorFiles,
        onEvent = onEvent,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        enabled = true,
      )
    }
  }
}

private fun previewLanguage(
  code: String,
  name: String,
) = Language(
  code = code,
  displayName = name,
  shortDisplayName = name,
  tessName = code,
  script = "Latn",
  dictionaryCode = code,
  tessdataSizeBytes = 0,
)

@Preview(showBackground = true)
@Composable
fun DetectedLanguageToastPreview() {
  TranslatorTheme {
    DetectedLanguageToast(
      detectedLanguage = previewLanguage("es", "Spanish"),
      availableLanguages = mapOf(previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true)),
      onSwitchClick = {},
      onEvent = {},
      downloadStates = emptyMap(),
    )
  }
}

@Preview(
  showBackground = true,
  uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun DetectedLanguageToastDarkPreview() {
  TranslatorTheme {
    DetectedLanguageToast(
      detectedLanguage = previewLanguage("fr", "French"),
      availableLanguages = mapOf(previewLanguage("fr", "French") to LangAvailability(true, true, true, true)),
      onSwitchClick = {},
      onEvent = {},
      downloadStates = emptyMap(),
    )
  }
}

@Preview(
  showBackground = true,
  uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun MissingLanguage() {
  TranslatorTheme {
    DetectedLanguageToast(
      detectedLanguage = previewLanguage("es", "Spanish"),
      availableLanguages = mapOf(previewLanguage("fr", "French") to LangAvailability(false, false, true, true)),
      onSwitchClick = {},
      onEvent = {},
      downloadStates = emptyMap(),
    )
  }
}

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

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.DownloadState
import dev.davidv.translator.LangAvailability
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityEntry
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.ui.theme.TranslatorTheme

@Composable
fun DetectedLanguageSection(
  detectedLanguage: Language?,
  from: Language,
  languageState: LanguageAvailabilityState,
  onMessage: (TranslatorMessage) -> Unit,
  downloadStates: Map<Language, DownloadState>,
  onEvent: (LanguageEvent) -> Unit,
) {
  if (detectedLanguage != null && detectedLanguage != from) {
    DetectedLanguageToast(
      detectedLanguage = detectedLanguage,
      languageState = languageState,
      onSwitchClick = {
        onMessage(TranslatorMessage.FromLang(detectedLanguage))
      },
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
      downloadStates = downloadStates,
      onEvent = onEvent,
    )
  }
}

private fun previewLanguageState(entries: List<Pair<Language, LangAvailability>>) =
  LanguageAvailabilityState(
    hasLanguages = true,
    availableLanguages = entries.map { (language, availability) -> LanguageAvailabilityEntry(language, availability) },
    isChecking = false,
  )

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
fun DetectedLanguageSectionPreview() {
  TranslatorTheme {
    DetectedLanguageSection(
      detectedLanguage = previewLanguage("fr", "French"),
      from = previewLanguage("en", "English"),
      languageState =
        previewLanguageState(
          listOf(
            previewLanguage("en", "English") to LangAvailability(true, true, true, true),
            previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
            previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
            previewLanguage("de", "German") to LangAvailability(false, false, true, true),
          ),
        ),
      onMessage = {},
      downloadStates = emptyMap(),
      onEvent = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
fun DetectedLanguageSectionNoDetectionPreview() {
  TranslatorTheme {
    DetectedLanguageSection(
      detectedLanguage = null,
      from = previewLanguage("en", "English"),
      languageState =
        previewLanguageState(
          listOf(
            previewLanguage("en", "English") to LangAvailability(true, true, true, true),
            previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
            previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
          ),
        ),
      onMessage = {},
      downloadStates = emptyMap(),
      onEvent = {},
    )
  }
}

@Preview(
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun DetectedLanguageSectionDarkPreview() {
  TranslatorTheme {
    DetectedLanguageSection(
      detectedLanguage = previewLanguage("de", "German"),
      from = previewLanguage("es", "Spanish"),
      languageState =
        previewLanguageState(
          listOf(
            previewLanguage("en", "English") to LangAvailability(true, true, true, true),
            previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
            previewLanguage("de", "German") to LangAvailability(true, true, true, true),
          ),
        ),
      onMessage = {},
      downloadStates = emptyMap(),
      onEvent = {},
    )
  }
}

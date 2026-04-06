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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.LangAvailability
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.canSwapLanguages
import dev.davidv.translator.ui.theme.TranslatorTheme

@Composable
fun LanguageSelectionRow(
  from: Language,
  to: Language,
  availableLanguages: Map<Language, LangAvailability>,
  languageMetadata: Map<Language, LanguageMetadata>,
  onMessage: (TranslatorMessage) -> Unit,
  onSettings: (() -> Unit)?,
  drawable: Pair<String, Int>,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val fromLanguages =
      Language.entries.filter { x ->
        x != to && x != from && (availableLanguages[x]?.hasToEnglish == true || x == Language.ENGLISH)
      }
    val toLanguages =
      Language.entries.filter { x ->
        x != from && x != to && (availableLanguages[x]?.hasFromEnglish == true || x == Language.ENGLISH)
      }

    LanguageSelector(
      selectedLanguage = from,
      availableLanguages = fromLanguages,
      languageMetadata = languageMetadata,
      onLanguageSelected = { language ->
        onMessage(TranslatorMessage.FromLang(language))
      },
      modifier = Modifier.weight(1f),
    )

    val canSwap = canSwapLanguages(from, to, availableLanguages)
    IconButton(
      onClick = { onMessage(TranslatorMessage.SwapLanguages) },
      enabled = canSwap,
    ) {
      Icon(
        painterResource(id = R.drawable.compare),
        contentDescription = "Reverse translation direction",
      )
    }

    LanguageSelector(
      selectedLanguage = to,
      availableLanguages = toLanguages,
      languageMetadata = languageMetadata,
      onLanguageSelected = { language ->
        onMessage(TranslatorMessage.ToLang(language))
      },
      modifier = Modifier.weight(1f),
    )

    if (onSettings != null) {
      IconButton(onClick = onSettings) {
        Icon(
          painterResource(id = drawable.second),
          contentDescription = drawable.first,
        )
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun LanguageSelectionRowPreview() {
  TranslatorTheme {
    LanguageSelectionRow(
      from = Language.ENGLISH,
      to = Language.SPANISH,
      availableLanguages = previewAvailability(),
      languageMetadata = mapOf(Language.SPANISH to LanguageMetadata(favorite = true)),
      onMessage = {},
      onSettings = {},
      drawable = Pair("Settings", R.drawable.settings),
    )
  }
}

private fun previewAvailability() =
  mapOf(
    Language.ENGLISH to LangAvailability(hasFromEnglish = true, hasToEnglish = true, ocrFiles = true, dictionaryFiles = false),
    Language.SPANISH to LangAvailability(hasFromEnglish = true, hasToEnglish = true, ocrFiles = true, dictionaryFiles = false),
    Language.FRENCH to LangAvailability(hasFromEnglish = true, hasToEnglish = true, ocrFiles = true, dictionaryFiles = false),
    Language.GERMAN to LangAvailability(hasFromEnglish = true, hasToEnglish = true, ocrFiles = true, dictionaryFiles = false),
  )

@Preview(
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun LanguageSelectionRowDarkPreview() {
  TranslatorTheme {
    LanguageSelectionRow(
      from = Language.FRENCH,
      to = Language.GERMAN,
      availableLanguages = previewAvailability(),
      languageMetadata = mapOf(Language.FRENCH to LanguageMetadata(favorite = true)),
      onMessage = {},
      onSettings = {},
      drawable = Pair("Settings", R.drawable.settings),
    )
  }
}

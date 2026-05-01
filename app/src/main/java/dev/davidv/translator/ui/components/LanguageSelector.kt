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

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.R
import dev.davidv.translator.ui.theme.TranslatorTheme

@Composable
fun LanguageSelector(
  selectedLanguage: Language,
  availableLanguages: List<Language>,
  languageMetadata: Map<Language, LanguageMetadata>,
  onLanguageSelected: (Language) -> Unit,
  modifier: Modifier = Modifier,
  isAutoSource: Boolean = false,
  detectedInstalled: Language? = null,
  showAutoOption: Boolean = false,
  onAutoSelected: (() -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  val interactionSource = remember { MutableInteractionSource() }

  val displayText =
    when {
      isAutoSource && detectedInstalled != null -> "✨ ${detectedInstalled.displayName}"
      isAutoSource -> "✨ Auto"
      else -> selectedLanguage.displayName
    }

  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    Text(
      modifier =
        Modifier
          .basicMarquee()
          .clip(RoundedCornerShape(10.dp))
          .clickable(
            interactionSource = interactionSource,
            indication = null,
          ) {
            expanded = true
          }
          .fillMaxWidth()
          .padding(vertical = 8.dp, horizontal = 4.dp),
      text = displayText,
      textAlign = TextAlign.Center,
      maxLines = 1,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      if (showAutoOption && onAutoSelected != null) {
        DropdownMenuItem(
          text = {
            Text(
              text = "✨ Auto",
              color =
                if (isAutoSource) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
            )
          },
          onClick = {
            onAutoSelected()
            expanded = false
          },
        )
        HorizontalDivider()
      }
      availableLanguages
        .sortedWith(
          compareByDescending<Language> { languageMetadata[it]?.favorite ?: false }
            .thenBy { it.displayName },
        ).forEach { language ->
          val isFavorite = languageMetadata[language]?.favorite ?: false
          DropdownMenuItem(
            text = {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(language.displayName)
                if (isFavorite) {
                  Icon(
                    painter = painterResource(id = R.drawable.star_filled),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.width(10.dp),
                  )
                }
              }
            },
            onClick = {
              onLanguageSelected(language)
              expanded = false
            },
          )
        }
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
fun LanguageSelectorAzerbaijaniPreview() {
  TranslatorTheme {
    Surface(
      color = MaterialTheme.colorScheme.surface,
    ) {
      LanguageSelector(
        selectedLanguage = previewLanguage("az", "Azerbaijani"),
        availableLanguages =
          listOf(
            previewLanguage("en", "English"),
            previewLanguage("es", "Spanish"),
            previewLanguage("fr", "French"),
            previewLanguage("az", "Azerbaijani"),
            previewLanguage("de", "German"),
          ),
        languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
        onLanguageSelected = { },
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun LanguageSelectorSpanishPreview() {
  TranslatorTheme {
    Surface(
      color = MaterialTheme.colorScheme.surface,
    ) {
      LanguageSelector(
        selectedLanguage = previewLanguage("es", "Spanish"),
        availableLanguages =
          listOf(
            previewLanguage("en", "English"),
            previewLanguage("es", "Spanish"),
            previewLanguage("fr", "French"),
            previewLanguage("az", "Azerbaijani"),
            previewLanguage("de", "German"),
          ),
        languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
        onLanguageSelected = { },
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun LanguageSelectorAutoNoDetectionPreview() {
  TranslatorTheme {
    Surface(
      color = MaterialTheme.colorScheme.surface,
    ) {
      LanguageSelector(
        selectedLanguage = previewLanguage("en", "English"),
        availableLanguages =
          listOf(
            previewLanguage("en", "English"),
            previewLanguage("es", "Spanish"),
          ),
        languageMetadata = emptyMap(),
        onLanguageSelected = { },
        isAutoSource = true,
        detectedInstalled = null,
        showAutoOption = true,
        onAutoSelected = {},
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun LanguageSelectorAutoSpanishPreview() {
  TranslatorTheme {
    Surface(
      color = MaterialTheme.colorScheme.surface,
    ) {
      LanguageSelector(
        selectedLanguage = previewLanguage("es", "Spanish"),
        availableLanguages =
          listOf(
            previewLanguage("en", "English"),
            previewLanguage("es", "Spanish"),
          ),
        languageMetadata = emptyMap(),
        onLanguageSelected = { },
        isAutoSource = true,
        detectedInstalled = previewLanguage("es", "Spanish"),
        showAutoOption = true,
        onAutoSelected = {},
      )
    }
  }
}

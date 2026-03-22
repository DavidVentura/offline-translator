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

package dev.davidv.translator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.ui.components.LanguageDownloadButton
import dev.davidv.translator.ui.components.LanguageEvent
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed class FavoriteEvent {
  data class Star(
    val language: Language,
  ) : FavoriteEvent()

  data class Unstar(
    val language: Language,
  ) : FavoriteEvent()
}

@Composable
fun LanguageManagerScreen(
  embedded: Boolean = false,
  title: String = "Language Packs",
  installedLanguages: List<Language>,
  availableLanguages: List<Language>,
  languageAvailabilityState: LanguageAvailabilityState,
  downloadStates: Map<Language, DownloadState>,
  languageMetadata: Map<Language, LanguageMetadata>,
  availabilityCheck: (LangAvailability) -> Boolean,
  onEvent: (LanguageEvent) -> Unit,
  onFavorite: ((FavoriteEvent) -> Unit)?,
  openDialog: Boolean = false,
  description: (Language) -> String,
  sizeBytes: (Language) -> Long,
  enabled: (Language) -> Boolean,
) {
  var showDownloadAllDialog by remember { mutableStateOf(openDialog) }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
  ) { paddingValues ->

    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .then(if (!embedded) Modifier.padding(paddingValues) else Modifier)
          .padding(horizontal = 16.dp, vertical = if (embedded) 0.dp else 16.dp),
    ) {
      if (!embedded) {
        Text(
          text = title,
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.padding(bottom = 16.dp),
        )
      }

      LazyColumn(
        verticalArrangement = Arrangement.spacedBy(0.dp),
      ) {
        // Installed Languages Section
        if (installedLanguages.isNotEmpty()) {
          item {
            Text(
              text = "Installed",
              style = MaterialTheme.typography.titleLarge,
              modifier = Modifier.padding(vertical = 8.dp),
            )
          }

          items(installedLanguages) { lang ->
            LanguageItem(
              lang = lang,
              state = languageAvailabilityState.availableLanguageMap[lang]!!,
              downloadState = downloadStates[lang],
              isFavorite = languageMetadata[lang]?.favorite ?: false,
              availabilityCheck = availabilityCheck,
              onEvent = onEvent,
              onFavorite = onFavorite,
              description = description,
              stateEnabled = enabled(lang),
            )
          }
        }

        // Available Languages Section
        if (availableLanguages.isNotEmpty()) {
          item {
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(vertical = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Available",
                style = MaterialTheme.typography.titleLarge,
              )
              Button(
                onClick = { showDownloadAllDialog = true },
                shape = ButtonDefaults.outlinedShape,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                colors = ButtonDefaults.textButtonColors(),
              ) {
                Text(
                  text = "Download all",
                  style = MaterialTheme.typography.labelMedium,
                )
              }
            }
          }

          items(availableLanguages) { lang ->
            LanguageItem(
              state = languageAvailabilityState.availableLanguageMap[lang] ?: LangAvailability(false, false, false),
              lang = lang,
              downloadState = downloadStates[lang],
              isFavorite = languageMetadata[lang]?.favorite ?: false,
              availabilityCheck = availabilityCheck,
              description = description,
              onEvent = onEvent,
              onFavorite = onFavorite,
              stateEnabled = enabled(lang),
            )
          }
        }
      }
    }
  }

  if (showDownloadAllDialog) {
    val totalSizeBytes = availableLanguages.sumOf { sizeBytes(it) }
    val totalSizeMiB = totalSizeBytes / (1024.0 * 1024.0)
    val totalSizeGiB = totalSizeMiB / 1024.0
    val sizeStr =
      if (totalSizeMiB > 100) {
        "%.2f GiB".format(totalSizeGiB)
      } else {
        "%.0f MiB".format(totalSizeMiB)
      }

    AlertDialog(
      onDismissRequest = { showDownloadAllDialog = false },
      title = {
        Text(
          "Download ${availableLanguages.size} languages?",
          style = MaterialTheme.typography.titleLarge,
        )
      },
      text = {
        Text(
          "Download size: $sizeStr\n\n" +
            "Make sure you've configured your storage location (internal/external) in settings first.",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            availableLanguages.forEach { language ->
              onEvent(LanguageEvent.Download(language))
            }
            showDownloadAllDialog = false
          },
        ) {
          Text("Download")
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showDownloadAllDialog = false },
        ) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
fun FavoriteButton(
  isFavorite: Boolean,
  language: Language,
  onEvent: (FavoriteEvent) -> Unit,
) {
  IconButton(
    onClick = {
      if (isFavorite) {
        onEvent(FavoriteEvent.Unstar(language))
      } else {
        onEvent(FavoriteEvent.Star(language))
      }
    },
  ) {
    Icon(
      painter = painterResource(id = if (isFavorite) R.drawable.star_filled else R.drawable.star),
      contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
      tint = if (isFavorite) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
  }
}

@Composable
private fun LanguageItem(
  lang: Language,
  state: LangAvailability,
  downloadState: DownloadState?,
  isFavorite: Boolean,
  description: (Language) -> String,
  availabilityCheck: (LangAvailability) -> Boolean,
  onEvent: (LanguageEvent) -> Unit,
  onFavorite: ((FavoriteEvent) -> Unit)?,
  stateEnabled: Boolean,
) {
  val isAvailable = availabilityCheck(state)
  Row(
    modifier =
      Modifier
        .padding(0.dp)
        .fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(
        text = lang.displayName,
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = description(lang),
        style = MaterialTheme.typography.labelMedium,
      )
    }
    Row(
      horizontalArrangement = Arrangement.End,
    ) {
      if (isAvailable && onFavorite != null) {
        FavoriteButton(isFavorite = isFavorite, language = lang, onEvent = onFavorite)
      }
      LanguageDownloadButton(
        language = lang,
        downloadState = downloadState,
        isLanguageAvailable = isAvailable,
        onEvent = onEvent,
        enabled = stateEnabled,
      )
    }
  }
}

fun missingFiles(
  dataFiles: Set<String>,
  lang: Language,
): Pair<Int, List<ModelFile>> {
  val (toSize, toFiles) = missingFilesTo(dataFiles, lang)
  val (fromSize, fromFiles) = missingFilesFrom(dataFiles, lang)
  return Pair(toSize + fromSize, toFiles + fromFiles)
}

fun missingFilesFrom(
  dataFiles: Set<String>,
  lang: Language,
): Pair<Int, List<ModelFile>> {
  val languageFiles = fromEnglishFiles[lang]!!
  val allModelFiles = listOf(languageFiles.model, languageFiles.srcVocab, languageFiles.tgtVocab, languageFiles.lex).distinctBy { it.name }
  val missing = allModelFiles.filter { it.name !in dataFiles }
  val totalSize = missing.sumOf { it.size }
  return Pair(totalSize, missing)
}

fun missingFilesTo(
  dataFiles: Set<String>,
  lang: Language,
): Pair<Int, List<ModelFile>> {
  val languageFiles = toEnglishFiles[lang]!!
  val allModelFiles = listOf(languageFiles.model, languageFiles.srcVocab, languageFiles.tgtVocab, languageFiles.lex).distinctBy { it.name }
  val missing = allModelFiles.filter { it.name !in dataFiles }
  val totalSize = missing.sumOf { it.size }
  return Pair(totalSize, missing)
}

fun getAvailableTessLanguages(tessData: File): List<Language> = Language.entries.filter { File(tessData, it.tessFilename).exists() }

data class PreviewStates(
  val languageState: StateFlow<LanguageAvailabilityState>,
  val downloadStates: StateFlow<Map<Language, DownloadState>>,
)

fun createPreviewStates(): PreviewStates =
  PreviewStates(
    languageState =
      MutableStateFlow(
        LanguageAvailabilityState(
          availableLanguageMap =
            mapOf(
              Language.ENGLISH to LangAvailability(true, true, true),
              Language.FRENCH to LangAvailability(true, true, false),
              Language.SPANISH to LangAvailability(true, true, true),
            ),
        ),
      ),
    downloadStates =
      MutableStateFlow(
        mapOf(
          Language.ARABIC to DownloadState(isDownloading = true, totalSize = 10, downloaded = 5),
          Language.ALBANIAN to DownloadState(isCancelled = true),
        ),
      ),
  )

@Composable
@Preview
fun LanguageManagerPreviewDark() {
  val states = createPreviewStates()
  val languageAvailabilityState by states.languageState.collectAsState()
  val downloadStates by states.downloadStates.collectAsState()
  val availLangs = languageAvailabilityState.availableLanguageMap.filterValues { it.translatorFiles }.keys
  val installedLanguages = availLangs.filter { it != Language.ENGLISH }.sortedBy { it.displayName }
  val availableLanguages =
    Language.entries
      .filter { lang ->
        fromEnglishFiles[lang] != null && !availLangs.contains(lang) && lang != Language.ENGLISH
      }.sortedBy { it.displayName }
      .take(4)

  TranslatorTheme(darkTheme = true) {
    LanguageManagerScreen(
      installedLanguages = installedLanguages,
      availableLanguages = availableLanguages,
      languageAvailabilityState = languageAvailabilityState,
      downloadStates = downloadStates,
      languageMetadata = mapOf(Language.SPANISH to LanguageMetadata(favorite = true)),
      availabilityCheck = { it.translatorFiles },
      onEvent = {},
      onFavorite = {},
      description = { "3MB" },
      sizeBytes = { 5 },
      enabled = { true },
    )
  }
}

@Composable
@Preview
fun LanguageManagerPreview() {
  val states = createPreviewStates()
  val languageAvailabilityState by states.languageState.collectAsState()
  val downloadStates by states.downloadStates.collectAsState()
  val availLangs = languageAvailabilityState.availableLanguageMap.filterValues { it.translatorFiles }.keys
  val installedLanguages = availLangs.filter { it != Language.ENGLISH }.sortedBy { it.displayName }
  val availableLanguages =
    Language.entries
      .filter { lang ->
        fromEnglishFiles[lang] != null && !availLangs.contains(lang) && lang != Language.ENGLISH
      }.sortedBy { it.displayName }
      .take(4)

  TranslatorTheme {
    LanguageManagerScreen(
      installedLanguages = installedLanguages,
      availableLanguages = availableLanguages,
      languageAvailabilityState = languageAvailabilityState,
      downloadStates = downloadStates,
      languageMetadata = mapOf(Language.SPANISH to LanguageMetadata(favorite = true)),
      availabilityCheck = { it.translatorFiles },
      onEvent = {},
      onFavorite = {},
      description = { "3MB" },
      sizeBytes = { 5 },
      enabled = { true },
    )
  }
}

@Composable
@Preview
fun LanguageManagerPreviewEmbedded() {
  val availableLanguages =
    Language.entries
      .filter { lang ->
        fromEnglishFiles[lang] != null && lang != Language.ENGLISH
      }.sortedBy { it.displayName }
  TranslatorTheme {
    LanguageManagerScreen(
      embedded = true,
      installedLanguages = emptyList(),
      availableLanguages = availableLanguages,
      languageAvailabilityState = LanguageAvailabilityState(),
      downloadStates = emptyMap(),
      languageMetadata = emptyMap(),
      availabilityCheck = { it.translatorFiles },
      onEvent = {},
      onFavorite = {},
      description = { "4MB" },
      sizeBytes = { 5 },
      enabled = { true },
    )
  }
}

@Composable
@Preview
fun LanguageManagerDialogPreview() {
  val states = createPreviewStates()
  val languageAvailabilityState by states.languageState.collectAsState()
  val downloadStates by states.downloadStates.collectAsState()
  val availLangs = languageAvailabilityState.availableLanguageMap.filterValues { it.translatorFiles }.keys
  val installedLanguages = availLangs.filter { it != Language.ENGLISH }.sortedBy { it.displayName }
  val availableLanguages =
    Language.entries
      .filter { lang ->
        fromEnglishFiles[lang] != null && !availLangs.contains(lang) && lang != Language.ENGLISH
      }.sortedBy { it.displayName }

  TranslatorTheme {
    LanguageManagerScreen(
      installedLanguages = installedLanguages,
      availableLanguages = availableLanguages,
      languageAvailabilityState = languageAvailabilityState,
      downloadStates = downloadStates,
      languageMetadata = mapOf(Language.SPANISH to LanguageMetadata(favorite = true)),
      availabilityCheck = { it.translatorFiles },
      onEvent = {},
      onFavorite = {},
      openDialog = true,
      description = { "5MB" },
      sizeBytes = { it.sizeBytes.toLong() },
      enabled = { true },
    )
  }
}

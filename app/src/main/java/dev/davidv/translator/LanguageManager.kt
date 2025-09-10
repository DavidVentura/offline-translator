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

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.ui.components.LanguageDownloadButton
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class PreviewStates(
  val languageState: StateFlow<LanguageAvailabilityState>,
  val downloadStates: StateFlow<Map<Language, DownloadState>>,
)

fun createPreviewStates(): PreviewStates =
  PreviewStates(
    languageState =
      MutableStateFlow(
        LanguageAvailabilityState(
          availableLanguages = listOf(Language.ENGLISH, Language.FRENCH, Language.SPANISH),
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
fun LanguageManagerPreview() {
  val states = createPreviewStates()
  TranslatorTheme {
    LanguageManagerScreen(
      languageState = states.languageState,
      downloadStates_ = states.downloadStates,
    )
  }
}

@Composable
@Preview
fun LanguageManagerPreviewEmbedded() {
  TranslatorTheme {
    LanguageManagerScreen(
      languageState =
        MutableStateFlow(
          LanguageAvailabilityState(
            availableLanguages = emptyList(),
          ),
        ),
      downloadStates_ = MutableStateFlow(emptyMap()),
      embedded = true,
    )
  }
}

@Composable
@Preview
fun LanguageManagerDialogPreview() {
  val states = createPreviewStates()
  TranslatorTheme {
    LanguageManagerScreen(
      languageState = states.languageState,
      downloadStates_ = states.downloadStates,
      openDialog = true,
    )
  }
}

@Composable
fun LanguageManagerScreen(
  embedded: Boolean = false,
  languageState: StateFlow<LanguageAvailabilityState>,
  downloadStates_: StateFlow<Map<Language, DownloadState>>?,
  openDialog: Boolean = false,
) {
  val context = LocalContext.current

  val languageAvailabilityState by languageState.collectAsState()
  val downloadStates by downloadStates_?.collectAsState() ?: remember { mutableStateOf(emptyMap()) }
  // Separate languages into installed and available
  val installedLanguages = languageAvailabilityState.availableLanguages.filter { it != Language.ENGLISH }.sortedBy { it.displayName }
  val availableLanguages =
    Language.entries
      .filter { lang ->
        fromEnglishFiles[lang] != null && !languageAvailabilityState.availableLanguages.contains(lang) && lang != Language.ENGLISH
      }.sortedBy { it.displayName }

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
          text = "Language Packs",
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
              fullyDownloaded = true,
              downloadState = downloadStates[lang],
              context = context,
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
              fullyDownloaded = false,
              lang = lang,
              downloadState = downloadStates[lang],
              context = context,
            )
          }
        }
      }
    }
  }

  if (showDownloadAllDialog) {
    val totalSizeBytes = availableLanguages.sumOf { it.sizeBytes }
    val totalSizeGiB = totalSizeBytes / (1024.0 * 1024.0 * 1024.0)

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
          "Download size: %.2f GiB\n\n".format(totalSizeGiB) +
            "Make sure you've configured your storage location (internal/external) in settings first.",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            availableLanguages.forEach { language ->
              DownloadService.startDownload(context, language)
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
private fun LanguageItem(
  lang: Language,
  fullyDownloaded: Boolean,
  downloadState: DownloadState?,
  context: Context,
) {
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
        text = "${(lang.sizeBytes / (1024 * 1024))} MB",
        style = MaterialTheme.typography.labelMedium,
      )
    }
    LanguageDownloadButton(lang, downloadState, context, fullyDownloaded)
  }
}

fun missingFiles(
  dataPath: File,
  lang: Language,
): Pair<Int, List<String>> {
  val (toSize, toFiles) = missingFilesTo(dataPath, lang)
  val (fromSize, fromFiles) = missingFilesFrom(dataPath, lang)
  return Pair(toSize + fromSize, toFiles + fromFiles)
}

fun missingFilesFrom(
  dataPath: File,
  lang: Language,
): Pair<Int, List<String>> {
  val languageFiles = fromEnglishFiles[lang]!!
  val fileSizePairs = listOf(languageFiles.model, languageFiles.srcVocab, languageFiles.tgtVocab, languageFiles.lex).distinct()
  val missingFiles = fileSizePairs.filter { (filename, _) -> !File(dataPath, filename).exists() }
  val totalSize = missingFiles.sumOf { (_, size) -> size }
  val filenames = missingFiles.map { (filename, _) -> filename }
  return Pair(totalSize, filenames)
}

fun missingFilesTo(
  dataPath: File,
  lang: Language,
): Pair<Int, List<String>> {
  val languageFiles = toEnglishFiles[lang]!!
  val fileSizePairs = listOf(languageFiles.model, languageFiles.srcVocab, languageFiles.tgtVocab, languageFiles.lex).distinct()
  val missingFiles = fileSizePairs.filter { (filename, _) -> !File(dataPath, filename).exists() }
  val totalSize = missingFiles.sumOf { (_, size) -> size }
  val filenames = missingFiles.map { (filename, _) -> filename }
  return Pair(totalSize, filenames)
}

fun getAvailableTessLanguages(tessData: File): List<Language> = Language.entries.filter { File(tessData, it.tessFilename).exists() }

fun getDownloadedLanguages(dataPath: File): List<Language> {
  return Language.entries.filter { lang ->
    if (lang == Language.ENGLISH) return@filter true
    val toEnglishExists = toEnglishFiles.containsKey(lang) && missingFilesTo(dataPath, lang).second.isEmpty()
    val fromEnglishExists = fromEnglishFiles.containsKey(lang) && missingFilesFrom(dataPath, lang).second.isEmpty()
    toEnglishExists || fromEnglishExists
  }
}

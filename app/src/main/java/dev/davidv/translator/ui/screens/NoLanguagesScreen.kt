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

package dev.davidv.translator.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.AppSettings
import dev.davidv.translator.DownloadEvent
import dev.davidv.translator.DownloadService
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.R
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoLanguagesScreen(
  onDone: () -> Unit,
  onSettings: () -> Unit,
  languageStateManager: LanguageStateManager,
  languageMetadataManager: dev.davidv.translator.LanguageMetadataManager,
  downloadService: DownloadService,
) {
  val state by languageStateManager.languageState.collectAsState()
  val context = LocalContext.current

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Language Setup") },
        actions = {
          IconButton(onClick = onSettings) {
            Icon(
              painterResource(id = R.drawable.settings),
              contentDescription = "Settings",
            )
          }
        },
      )
    },
    bottomBar = {
      Button(
        onClick = onDone,
        enabled = state.hasLanguages,
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .navigationBarsPadding(),
      ) {
        Text("Done")
      }
    },
  ) { paddingValues ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(paddingValues)
          .padding(horizontal = 16.dp),
    ) {
      Text(
        text = "Download language packs to start translating",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
      )

      val downloadStates by downloadService.downloadStates.collectAsState()
      val langIndex by languageStateManager.languageIndex.collectAsState()
      val dictionaryDownloadStates by downloadService.dictionaryDownloadStates.collectAsState()
      val dictionaryIndex by languageStateManager.dictionaryIndex.collectAsState()

      LanguageAssetManagerScreen(
        context = context,
        languageStateManager = languageStateManager,
        languageMetadataManager = languageMetadataManager,
        languageIndex = langIndex,
        languageAvailabilityState = state,
        downloadStates = downloadStates,
        dictionaryDownloadStates = dictionaryDownloadStates,
        dictionaryIndex = dictionaryIndex,
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun NoLanguagesScreenPreview() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val appSettingsFlow = MutableStateFlow(AppSettings())
  val fp = FilePathManager(context, appSettingsFlow)
  val mockDownloadEvents = MutableSharedFlow<DownloadEvent>()
  val downloadService = DownloadService()
  val languagesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Language>>(emptyList())
  TranslatorTheme {
    NoLanguagesScreen(
      onDone = {},
      onSettings = {},
      downloadService = downloadService,
      languageStateManager = LanguageStateManager(scope, fp, mockDownloadEvents),
      languageMetadataManager = LanguageMetadataManager(context, languagesFlow),
    )
  }
}

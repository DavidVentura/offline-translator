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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.DownloadState
import dev.davidv.translator.Language
import dev.davidv.translator.R
import dev.davidv.translator.ui.theme.TranslatorTheme

@Composable
fun LanguageDownloadButton(
  language: Language,
  downloadState: DownloadState?,
  isLanguageAvailable: Boolean,
  onEvent: (LanguageEvent) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean,
) {
  val isDownloading = downloadState?.isDownloading == true

  if (isDownloading) {
    // Progress indicator with cancel button
    Box(
      contentAlignment = Alignment.Center,
      modifier = modifier.size(48.dp),
    ) {
      val targetProgress =
        downloadState!!.downloaded.toFloat() / downloadState.totalSize.toFloat()

      val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 300),
        label = "progress",
      )
      CircularProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier.size(40.dp),
      )
      IconButton(
        enabled = enabled,
        onClick = {
          onEvent(LanguageEvent.Cancel(language))
        },
        modifier = Modifier.size(40.dp),
      ) {
        Icon(
          painterResource(id = R.drawable.cancel),
          contentDescription = "Cancel Download",
        )
      }
    }
  } else if (isLanguageAvailable) {
    // Delete button for available/completed languages
    IconButton(
      enabled = enabled,
      onClick = {
        onEvent(LanguageEvent.Delete(language))
      },
      modifier = modifier,
    ) {
      Icon(
        painterResource(id = R.drawable.delete),
        contentDescription = "Delete Language",
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
      )
    }
  } else {
    // Download/retry button
    IconButton(
      enabled = enabled,
      onClick = {
        onEvent(LanguageEvent.Download(language))
      },
      modifier = modifier,
    ) {
      when {
        downloadState?.isCancelled == true || downloadState?.error != null -> {
          Icon(
            painterResource(id = R.drawable.refresh),
            contentDescription = "Retry Download",
          )
        }
        else -> {
          Icon(
            painterResource(id = R.drawable.add),
            contentDescription = "Download",
          )
        }
      }
    }
  }
}

sealed class LanguageEvent {
  data class Download(
    val language: Language,
  ) : LanguageEvent()

  data class Delete(
    val language: Language,
  ) : LanguageEvent()

  data class DeleteDictionary(
    val language: Language,
  ) : LanguageEvent()

  data class Cancel(
    val language: Language,
  ) : LanguageEvent()

  object FetchDictionaryIndex : LanguageEvent()
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
fun LanguageDownloadButtonPreview() {
  val french = previewLanguage("fr", "French")
  TranslatorTheme {
    Surface(
      modifier = Modifier.padding(16.dp),
      color = MaterialTheme.colorScheme.background,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        LanguageDownloadButton(
          language = french,
          downloadState = null,
          isLanguageAvailable = false,
          onEvent = {},
          enabled = true,
        )

        LanguageDownloadButton(
          language = french,
          downloadState = DownloadState(isDownloading = true, totalSize = 100, downloaded = 50),
          isLanguageAvailable = false,
          onEvent = {},
          enabled = true,
        )

        LanguageDownloadButton(
          language = french,
          downloadState = null,
          isLanguageAvailable = true,
          onEvent = {},
          enabled = false,
        )

        LanguageDownloadButton(
          language = french,
          downloadState = DownloadState(error = "Failed"),
          isLanguageAvailable = false,
          onEvent = {},
          enabled = true,
        )

        LanguageDownloadButton(
          language = french,
          downloadState = null,
          isLanguageAvailable = true,
          onEvent = {},
          enabled = true,
        )
      }
    }
  }
}

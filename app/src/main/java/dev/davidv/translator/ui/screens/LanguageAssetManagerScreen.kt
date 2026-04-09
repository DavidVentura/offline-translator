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

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.davidv.translator.DictionaryIndex
import dev.davidv.translator.DictionaryInfo
import dev.davidv.translator.DownloadService
import dev.davidv.translator.DownloadState
import dev.davidv.translator.FavoriteButton
import dev.davidv.translator.FavoriteEvent
import dev.davidv.translator.LangAvailability
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageIndex
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.R
import kotlin.math.roundToInt

private const val ROW_EXPAND_ANIMATION_MS = 140

private data class PendingSharedDictionaryDelete(
  val language: Language,
  val sharedWith: List<Language>,
  val alsoDeleteTranslation: Boolean,
)

private data class LanguageFeatureRow(
  val label: String,
  val secondaryLabel: String? = null,
  val installed: Boolean,
  val downloadState: DownloadState?,
  val onDownload: () -> Unit,
  val onDelete: () -> Unit,
  val onCancel: () -> Unit,
)

private data class LanguageAssetRow(
  val language: Language,
  val availability: LangAvailability,
  val dictionaryInfo: DictionaryInfo?,
  val translationVisible: Boolean,
  val dictionaryVisible: Boolean,
) {
  val translationInstalled: Boolean get() = translationVisible && availability.translatorFiles
  val dictionaryInstalled: Boolean get() = dictionaryVisible && availability.dictionaryFiles
  val visibleFeatureCount: Int get() = listOf(translationVisible, dictionaryVisible).count { it }
  val installedFeatureCount: Int get() = listOf(translationInstalled, dictionaryInstalled).count { it }
  val fullyInstalled: Boolean get() = visibleFeatureCount > 0 && installedFeatureCount == visibleFeatureCount
  val fullyMissing: Boolean get() = installedFeatureCount == 0
  val partiallyInstalled: Boolean get() = installedFeatureCount in 1 until visibleFeatureCount
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageAssetManagerScreen(
  context: Context,
  languageStateManager: LanguageStateManager,
  languageMetadataManager: LanguageMetadataManager,
  languageIndex: LanguageIndex?,
  languageAvailabilityState: LanguageAvailabilityState,
  downloadStates: Map<Language, DownloadState>,
  dictionaryDownloadStates: Map<Language, DownloadState>,
  dictionaryIndex: DictionaryIndex?,
) {
  val languageMetadata by languageMetadataManager.metadata.collectAsState()
  val expandedLanguages = remember { mutableStateMapOf<String, Boolean>() }
  var isRefreshing by remember { mutableStateOf(false) }
  var filterQuery by remember { mutableStateOf("") }
  var pendingSharedDictionaryDelete by remember { mutableStateOf<PendingSharedDictionaryDelete?>(null) }
  val languageIndexVersion by languageStateManager.languageIndexVersion.collectAsState()

  LaunchedEffect(languageIndexVersion) {
    isRefreshing = false
  }

  val normalizedFilter = filterQuery.trim().lowercase()
  val rows =
    languageIndex
      ?.languages
      ?.sortedBy { it.displayName }
      ?.mapNotNull { language ->
        val availability = languageAvailabilityState.availableLanguageMap[language] ?: LangAvailability(false, false, false, false)
        val dictInfo = dictionaryIndex?.infoFor(language)
        val translationVisible = !language.isEnglish
        val dictionaryVisible = dictInfo != null
        if (!translationVisible && !dictionaryVisible) {
          null
        } else {
          LanguageAssetRow(
            language = language,
            availability = availability,
            dictionaryInfo = dictInfo,
            translationVisible = translationVisible,
            dictionaryVisible = dictionaryVisible,
          )
        }
      }?.filter { row ->
        if (normalizedFilter.isBlank()) {
          true
        } else {
          val language = row.language
          val haystack =
            listOf(language.displayName, language.shortDisplayName, language.code)
              .joinToString(" ")
              .lowercase()
          normalizedFilter in haystack
        }
      }
      ?: emptyList()

  Scaffold(
    modifier =
      Modifier
        .fillMaxSize()
        .imePadding(),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
  ) { scaffoldPaddingValues ->
    PullToRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = {
        isRefreshing = true
        DownloadService.fetchLanguageIndex(context)
      },
      modifier =
        Modifier
          .fillMaxSize()
          .padding(scaffoldPaddingValues),
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
      ) {
        OutlinedTextField(
          value = filterQuery,
          onValueChange = { filterQuery = it },
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(top = 2.dp, bottom = 6.dp),
          singleLine = true,
          label = { Text("Filter languages") },
        )

        LazyColumn(
          modifier = Modifier.fillMaxSize(),
        ) {
          items(rows, key = { it.language.code }) { row ->
            val expanded = expandedLanguages[row.language.code] == true
            val sharedDictionaryUsers =
              rows
                .filter { other ->
                  other.language.code != row.language.code &&
                    other.dictionaryInstalled &&
                    other.language.dictionaryCode == row.language.dictionaryCode
                }.map { it.language }
                .sortedBy { it.displayName }
            LanguageAssetCard(
              row = row,
              expanded = expanded,
              isFavorite = languageMetadata[row.language]?.favorite ?: false,
              translationDownloadState = downloadStates[row.language],
              dictionaryDownloadState = dictionaryDownloadStates[row.language],
              onToggleExpanded = {
                expandedLanguages[row.language.code] = !expanded
              },
              onFavorite = { event ->
                when (event) {
                  is FavoriteEvent.Star -> {
                    val current = languageMetadata[event.language] ?: LanguageMetadata()
                    languageMetadataManager.updateLanguage(event.language, current.copy(favorite = true))
                  }

                  is FavoriteEvent.Unstar -> {
                    val current = languageMetadata[event.language] ?: LanguageMetadata()
                    languageMetadataManager.updateLanguage(event.language, current.copy(favorite = false))
                  }
                }
              },
              onDownloadTranslation = {
                DownloadService.startDownload(context, row.language)
              },
              onDeleteTranslation = {
                languageStateManager.deleteLanguage(row.language)
              },
              onCancelTranslation = {
                DownloadService.cancelDownload(context, row.language)
              },
              onDownloadDictionary = {
                DownloadService.startDictDownload(context, row.language, row.dictionaryInfo)
              },
              onDeleteDictionary = {
                if (sharedDictionaryUsers.isNotEmpty()) {
                  pendingSharedDictionaryDelete =
                    PendingSharedDictionaryDelete(
                      language = row.language,
                      sharedWith = sharedDictionaryUsers,
                      alsoDeleteTranslation = false,
                    )
                } else {
                  languageStateManager.deleteDict(row.language)
                }
              },
              onCancelDictionary = {
                DownloadService.cancelDictDownload(context, row.language)
              },
              onDownloadAll = {
                if (row.translationVisible && !row.translationInstalled) {
                  DownloadService.startDownload(context, row.language)
                }
                if (row.dictionaryVisible && !row.dictionaryInstalled) {
                  if (!row.language.isEnglish && !row.translationInstalled) {
                    DownloadService.startDownload(context, row.language)
                  }
                  DownloadService.startDictDownload(context, row.language, row.dictionaryInfo)
                }
              },
              onDeleteAll = {
                if (row.dictionaryInstalled && sharedDictionaryUsers.isNotEmpty()) {
                  pendingSharedDictionaryDelete =
                    PendingSharedDictionaryDelete(
                      language = row.language,
                      sharedWith = sharedDictionaryUsers,
                      alsoDeleteTranslation = row.translationInstalled,
                    )
                } else {
                  if (row.translationInstalled) {
                    languageStateManager.deleteLanguage(row.language)
                  }
                  if (row.dictionaryInstalled) {
                    languageStateManager.deleteDict(row.language)
                  }
                }
              },
              onCancelAll = {
                if (downloadStates[row.language]?.isDownloading == true) {
                  DownloadService.cancelDownload(context, row.language)
                }
                if (dictionaryDownloadStates[row.language]?.isDownloading == true) {
                  DownloadService.cancelDictDownload(context, row.language)
                }
              },
            )
          }
        }
      }
    }
  }

  pendingSharedDictionaryDelete?.let { pendingDelete ->
    val sharedNames = pendingDelete.sharedWith.joinToString(", ") { it.displayName }
    AlertDialog(
      onDismissRequest = { pendingSharedDictionaryDelete = null },
      title = { Text("Delete shared dictionary?") },
      text = {
        Text(
          "This dictionary is shared with $sharedNames.\nDeleting it will remove the dictionary for all of them.",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            if (pendingDelete.alsoDeleteTranslation) {
              languageStateManager.deleteLanguage(pendingDelete.language)
            }
            languageStateManager.deleteDict(pendingDelete.language)
            pendingSharedDictionaryDelete = null
          },
        ) {
          Text("OK")
        }
      },
      dismissButton = {
        TextButton(
          onClick = { pendingSharedDictionaryDelete = null },
        ) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun LanguageAssetCard(
  row: LanguageAssetRow,
  expanded: Boolean,
  isFavorite: Boolean,
  translationDownloadState: DownloadState?,
  dictionaryDownloadState: DownloadState?,
  onToggleExpanded: () -> Unit,
  onFavorite: (FavoriteEvent) -> Unit,
  onDownloadTranslation: () -> Unit,
  onDeleteTranslation: () -> Unit,
  onCancelTranslation: () -> Unit,
  onDownloadDictionary: () -> Unit,
  onDeleteDictionary: () -> Unit,
  onCancelDictionary: () -> Unit,
  onDownloadAll: () -> Unit,
  onDeleteAll: () -> Unit,
  onCancelAll: () -> Unit,
) {
  val featureRows =
    buildFeatureRows(
      row = row,
      translationDownloadState = translationDownloadState,
      dictionaryDownloadState = dictionaryDownloadState,
      onDownloadTranslation = onDownloadTranslation,
      onDeleteTranslation = onDeleteTranslation,
      onCancelTranslation = onCancelTranslation,
      onDownloadDictionary = onDownloadDictionary,
      onDeleteDictionary = onDeleteDictionary,
      onCancelDictionary = onCancelDictionary,
    )
  val totalVisibleSize =
    (if (row.translationVisible) row.language.sizeBytes else 0L) +
      (if (row.dictionaryVisible) row.dictionaryInfo?.size ?: 0L else 0L)
  val collapsedDownloadState =
    when {
      row.fullyMissing -> translationDownloadState ?: dictionaryDownloadState
      row.fullyInstalled -> null
      else -> null
    }

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .animateContentSize(animationSpec = tween(durationMillis = ROW_EXPAND_ANIMATION_MS))
        .padding(vertical = 1.dp),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable { onToggleExpanded() }
          .padding(vertical = 1.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = onToggleExpanded,
        modifier = Modifier.size(28.dp),
      ) {
        Icon(
          painter = painterResource(id = if (expanded) R.drawable.expandless else R.drawable.expandmore),
          contentDescription = if (expanded) "Collapse" else "Expand",
          modifier = Modifier.size(18.dp),
        )
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(0.dp),
      ) {
        Text(
          text = row.language.displayName,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = formatSize(totalVisibleSize),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      FavoriteButton(
        isFavorite = isFavorite,
        language = row.language,
        onEvent = onFavorite,
      )

      Box(
        modifier = Modifier.width(42.dp),
        contentAlignment = Alignment.CenterEnd,
      ) {
        when {
          expanded || row.partiallyInstalled -> {
            FeaturePresenceIndicators(row)
          }

          else -> {
            AggregateActionButton(
              downloadState = collapsedDownloadState,
              isInstalled = row.fullyInstalled,
              onDownload = onDownloadAll,
              onDelete = onDeleteAll,
              onCancel = onCancelAll,
            )
          }
        }
      }
    }

    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically(animationSpec = tween(ROW_EXPAND_ANIMATION_MS)) + fadeIn(animationSpec = tween(ROW_EXPAND_ANIMATION_MS)),
      exit = shrinkVertically(animationSpec = tween(ROW_EXPAND_ANIMATION_MS)) + fadeOut(animationSpec = tween(ROW_EXPAND_ANIMATION_MS / 2)),
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, end = 4.dp, bottom = 1.dp),
      ) {
        featureRows.forEach { featureRow ->
          FeatureRow(featureRow)
        }
      }
    }
  }
}

private fun buildFeatureRows(
  row: LanguageAssetRow,
  translationDownloadState: DownloadState?,
  dictionaryDownloadState: DownloadState?,
  onDownloadTranslation: () -> Unit,
  onDeleteTranslation: () -> Unit,
  onCancelTranslation: () -> Unit,
  onDownloadDictionary: () -> Unit,
  onDeleteDictionary: () -> Unit,
  onCancelDictionary: () -> Unit,
): List<LanguageFeatureRow> {
  val featureRows = mutableListOf<LanguageFeatureRow>()

  if (row.translationVisible) {
    featureRows +=
      LanguageFeatureRow(
        label = "Translation, ${formatSize(row.language.sizeBytes)}",
        installed = row.translationInstalled,
        downloadState = translationDownloadState,
        onDownload = onDownloadTranslation,
        onDelete = onDeleteTranslation,
        onCancel = onCancelTranslation,
      )
  }

  if (row.dictionaryVisible) {
    featureRows +=
      LanguageFeatureRow(
        label = "Dictionary, ${formatSize(row.dictionaryInfo?.size ?: 0L)}",
        secondaryLabel =
          buildDictionarySecondaryLabel(
            type = row.dictionaryInfo?.type,
            wordCount = row.dictionaryInfo?.wordCount ?: 0L,
          ),
        installed = row.dictionaryInstalled,
        downloadState = dictionaryDownloadState,
        onDownload = onDownloadDictionary,
        onDelete = onDeleteDictionary,
        onCancel = onCancelDictionary,
      )
  }

  return featureRows
}

@Composable
private fun FeatureRow(featureRow: LanguageFeatureRow) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(vertical = 0.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      Text(
        text = featureRow.label,
        style = MaterialTheme.typography.bodyMedium,
      )
      featureRow.secondaryLabel?.let { secondaryLabel ->
        Text(
          text = secondaryLabel,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    FeatureActionButton(
      downloadState = featureRow.downloadState,
      isInstalled = featureRow.installed,
      onDownload = featureRow.onDownload,
      onDelete = featureRow.onDelete,
      onCancel = featureRow.onCancel,
    )
  }
}

@Composable
private fun FeaturePresenceIndicators(row: LanguageAssetRow) {
  val installedTint = MaterialTheme.colorScheme.onSurface
  val missingTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (row.translationVisible) {
      Text(
        text = "T",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color =
          if (row.translationInstalled) {
            installedTint
          } else {
            missingTint
          },
      )
    }

    if (row.dictionaryVisible) {
      Icon(
        painter = painterResource(id = R.drawable.dictionary),
        contentDescription = "Dictionary Status",
        tint =
          if (row.dictionaryInstalled) {
            installedTint
          } else {
            missingTint
          },
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
private fun AggregateActionButton(
  downloadState: DownloadState?,
  isInstalled: Boolean,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
  onCancel: () -> Unit,
) {
  if (downloadState?.isDownloading == true) {
    ProgressIconButton(
      downloadState = downloadState,
      onClick = onCancel,
      contentDescription = "Cancel Download",
    )
    return
  }

  IconButton(
    onClick = if (isInstalled) onDelete else onDownload,
    modifier = Modifier.size(32.dp),
  ) {
    Icon(
      painter =
        painterResource(
          id =
            when {
              isInstalled -> R.drawable.delete
              downloadState?.isCancelled == true || downloadState?.error != null -> R.drawable.refresh
              else -> R.drawable.add
            },
        ),
      contentDescription =
        when {
          isInstalled -> "Delete"
          downloadState?.isCancelled == true || downloadState?.error != null -> "Retry Download"
          else -> "Download"
        },
      modifier = Modifier.size(18.dp),
    )
  }
}

@Composable
private fun FeatureActionButton(
  downloadState: DownloadState?,
  isInstalled: Boolean,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
  onCancel: () -> Unit,
) {
  if (downloadState?.isDownloading == true) {
    ProgressIconButton(
      downloadState = downloadState,
      onClick = onCancel,
      contentDescription = "Cancel Download",
    )
    return
  }

  IconButton(
    onClick = if (isInstalled) onDelete else onDownload,
    modifier = Modifier.size(32.dp),
  ) {
    Icon(
      painter =
        painterResource(
          id =
            when {
              isInstalled -> R.drawable.delete
              downloadState?.isCancelled == true || downloadState?.error != null -> R.drawable.refresh
              else -> R.drawable.add
            },
        ),
      contentDescription =
        when {
          isInstalled -> "Delete Feature"
          downloadState?.isCancelled == true || downloadState?.error != null -> "Retry Download"
          else -> "Download Feature"
        },
      modifier = Modifier.size(18.dp),
    )
  }
}

@Composable
private fun ProgressIconButton(
  downloadState: DownloadState,
  onClick: () -> Unit,
  contentDescription: String,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier.size(32.dp),
  ) {
    val targetProgress =
      if (downloadState.totalSize > 0) {
        downloadState.downloaded.toFloat() / downloadState.totalSize.toFloat()
      } else {
        0f
      }
    val animatedProgress by animateFloatAsState(
      targetValue = targetProgress,
      animationSpec = tween(durationMillis = 300),
      label = "progress",
    )

    CircularProgressIndicator(
      progress = { animatedProgress },
      modifier = Modifier.size(22.dp),
      strokeWidth = 2.dp,
    )
    IconButton(
      onClick = onClick,
      modifier = Modifier.size(28.dp),
    ) {
      Icon(
        painter = painterResource(id = R.drawable.cancel),
        contentDescription = contentDescription,
        modifier = Modifier.size(14.dp),
      )
    }
  }
}

private fun formatSize(sizeBytes: Long): String {
  val sizeMiB = sizeBytes / (1024f * 1024f)
  return if (sizeMiB < 1f) {
    "<1 MB"
  } else if (sizeMiB >= 10f) {
    "${sizeMiB.roundToInt()} MB"
  } else {
    String.format("%.2f MB", sizeMiB)
  }
}

private fun buildDictionarySecondaryLabel(
  type: String?,
  wordCount: Long,
): String? {
  val parts = mutableListOf<String>()
  dictionaryTypeLabel(type)?.let(parts::add)
  if (wordCount > 0L) {
    parts += "${humanCount(wordCount)} entries"
  }
  return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun dictionaryTypeLabel(type: String?): String? =
  when (type?.lowercase()) {
    null, "" -> null
    "english" -> "English"
    "bilingual" -> "Bilingual"
    else -> type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
  }

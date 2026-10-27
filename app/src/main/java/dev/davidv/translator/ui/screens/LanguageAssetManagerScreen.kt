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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davidv.translator.DictionaryInfo
import dev.davidv.translator.DownloadId
import dev.davidv.translator.DownloadManager
import dev.davidv.translator.DownloadRequest
import dev.davidv.translator.DownloadStatus
import dev.davidv.translator.Feature
import dev.davidv.translator.LangAvailability
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageCatalog
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.R
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.encodeVoiceOverride
import dev.davidv.translator.inFlightProgress
import dev.davidv.translator.languageNameComparator
import dev.davidv.translator.localizedName
import dev.davidv.translator.parseVoiceOverride
import dev.davidv.translator.ui.components.SamplePlaybackState
import dev.davidv.translator.ui.components.SamplePlayer
import dev.davidv.translator.ui.theme.AppLayout
import dev.davidv.translator.ui.theme.LayoutDensity
import java.io.File
import kotlin.math.roundToInt

private const val ROW_EXPAND_ANIMATION_MS = 140
private const val PPOCR_ENGINE = "ppocr"

private fun languageTtsDownloads(
  downloads: Map<DownloadId, DownloadStatus>,
  language: Language,
): Map<DownloadId, DownloadStatus> = downloads.filterKeys { it is DownloadId.Tts && it.language == language }

// A language's voice row stands for all of its queued packs: show the one transferring, else
// one waiting, else a failure to retry.
private fun languageTtsStatus(
  downloads: Map<DownloadId, DownloadStatus>,
  language: Language,
): DownloadStatus? {
  val statuses = languageTtsDownloads(downloads, language).values
  return statuses.firstOrNull { it is DownloadStatus.Running }
    ?: statuses.firstOrNull { it == DownloadStatus.Queued }
    ?: statuses.firstOrNull()
}

private sealed class FavoriteEvent {
  data class Star(
    val language: Language,
  ) : FavoriteEvent()

  data class Unstar(
    val language: Language,
  ) : FavoriteEvent()
}

private data class PendingSharedDictionaryDelete(
  val language: Language,
  val sharedWith: List<Language>,
  val deleteLanguage: Boolean,
  val deleteTts: Boolean,
)

private data class PendingTtsVoicePicker(
  val language: Language,
)

private data class PaddleUpgrade(
  val languages: List<Language>,
  val totalBytes: Long,
) {
  companion object {
    val EMPTY = PaddleUpgrade(emptyList(), 0L)
  }
}

private data class TranslationUpgrade(
  val languages: List<Language>,
  val totalBytes: Long,
) {
  companion object {
    val EMPTY = TranslationUpgrade(emptyList(), 0L)
  }
}

private data class TtsUpgrade(
  val languages: List<Language>,
  val totalBytes: Long,
) {
  companion object {
    val EMPTY = TtsUpgrade(emptyList(), 0L)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerDialog(
  context: Context,
  language: Language,
  catalog: LanguageCatalog,
  languageStateManager: LanguageStateManager,
  settingsManager: SettingsManager,
  downloads: Map<DownloadId, DownloadStatus>,
  onDownloadVoice: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val regions = catalog.ttsVoicePickerRegions(language.code)
  val installedRegions = catalog.installedTtsVoicePickerRegions(language.code)
  val installedPackIds =
    remember(installedRegions) {
      installedRegions.flatMap { region -> region.voices.map { it.packId } }.toSet()
    }
  val showRegions = regions.size > 1
  val voicesWithRegion =
    remember(regions, showRegions) {
      regions.flatMap { region ->
        region.voices.map { pack -> VoiceWithRegion(pack, region.displayName.takeIf { showRegions }) }
      }
    }
  val downloaded = voicesWithRegion.filter { it.pack.packId in installedPackIds }
  val available = voicesWithRegion.filterNot { it.pack.packId in installedPackIds }

  val settings by settingsManager.settings.collectAsState()
  val override = parseVoiceOverride(settings.ttsVoiceOverrides[language.code])
  val installedVoiceNames = downloaded.map { it.pack.voiceName }
  val effectiveDefaultVoiceName =
    override?.voiceName?.takeIf { it in installedVoiceNames }
      ?: installedVoiceNames.minOrNull()

  val initialDefaultVoiceName =
    remember(language.code) {
      val initialOverride =
        parseVoiceOverride(settingsManager.settings.value.ttsVoiceOverrides[language.code])
      val initialNames =
        catalog
          .installedTtsVoicePickerRegions(language.code)
          .flatMap { region -> region.voices.map { it.voiceName } }
      initialOverride?.voiceName?.takeIf { it in initialNames } ?: initialNames.minOrNull()
    }

  val orderedDownloaded =
    downloaded.sortedWith(
      compareByDescending<VoiceWithRegion> { it.pack.voiceName == initialDefaultVoiceName }
        .thenBy { it.regionName ?: "" }
        .thenBy { it.pack.displayName },
    )
  val orderedAvailable =
    available.sortedWith(
      compareBy<VoiceWithRegion> { it.regionName ?: "" }
        .thenBy { it.pack.displayName },
    )

  val playerScope = rememberCoroutineScope()
  val samplePlayer =
    remember(language.code) {
      SamplePlayer(
        cacheRoot = File(context.cacheDir, "tts_samples"),
        scope = playerScope,
      )
    }
  DisposableEffect(samplePlayer) {
    onDispose { samplePlayer.release() }
  }

  val setDefault: (String, String) -> Unit = { packId, voiceName ->
    settingsManager.update { current ->
      current.copy(
        ttsVoiceOverrides =
          current.ttsVoiceOverrides + (language.code to encodeVoiceOverride(packId, voiceName)),
      )
    }
  }

  BasicAlertDialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier.testTag("export-section:Voice picker"),
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 6.dp,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
      ) {
        Row(verticalAlignment = Alignment.Top) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = language.localizedName().uppercase(),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = stringResource(R.string.langmgr_voices),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.SemiBold,
            )
          }
          FilledTonalIconButton(
            onClick = onDismiss,
            modifier = Modifier.size(36.dp),
            colors =
              IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
              ),
          ) {
            Icon(
              painter = painterResource(id = R.drawable.cancel),
              contentDescription = stringResource(R.string.a11y_close),
              modifier = Modifier.size(18.dp),
            )
          }
        }
        Spacer(modifier = Modifier.size(16.dp))
        Column(
          modifier =
            Modifier
              .heightIn(max = 520.dp)
              .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          val dimAvailable = orderedDownloaded.isNotEmpty()
          if (orderedDownloaded.isNotEmpty()) {
            VoiceSectionHeader(stringResource(R.string.voice_section_downloaded))
            orderedDownloaded.forEach { entry ->
              VoiceRow(
                voice = entry,
                isInstalled = true,
                isDefault = entry.pack.voiceName == effectiveDefaultVoiceName,
                dimmed = false,
                player = samplePlayer,
                onTap = { setDefault(entry.pack.packId, entry.pack.voiceName) },
                downloadStatus = VoiceRowStatus.Idle,
                onAction = {
                  if (entry.pack.voiceName == effectiveDefaultVoiceName) {
                    val nextPack =
                      downloaded.firstOrNull { it.pack.packId != entry.pack.packId }?.pack
                    settingsManager.update { current ->
                      val newOverrides =
                        if (nextPack != null) {
                          current.ttsVoiceOverrides +
                            (language.code to encodeVoiceOverride(nextPack.packId, nextPack.voiceName))
                        } else {
                          current.ttsVoiceOverrides - language.code
                        }
                      current.copy(ttsVoiceOverrides = newOverrides)
                    }
                  }
                  languageStateManager.deleteTtsPack(language, entry.pack.packId)
                },
              )
            }
          }
          if (orderedAvailable.isNotEmpty()) {
            if (orderedDownloaded.isNotEmpty()) {
              HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp),
              )
            }
            VoiceSectionHeader(stringResource(R.string.voice_section_available))
            orderedAvailable.forEach { entry ->
              val downloadStatus =
                when (val status = downloads[DownloadId.Tts(language, entry.pack.packId)]) {
                  is DownloadStatus.Running -> VoiceRowStatus.Active(status.fraction)
                  DownloadStatus.Queued -> VoiceRowStatus.Queued
                  is DownloadStatus.Failed, null -> VoiceRowStatus.Idle
                }
              VoiceRow(
                voice = entry,
                isInstalled = false,
                isDefault = false,
                dimmed = dimAvailable,
                player = samplePlayer,
                onTap = null,
                onAction = {
                  if (downloadStatus == VoiceRowStatus.Idle) {
                    onDownloadVoice(entry.pack.packId)
                  }
                },
                downloadStatus = downloadStatus,
              )
            }
          }
        }
      }
    }
  }
}

private data class VoiceWithRegion(
  val pack: dev.davidv.translator.TtsVoicePackInfo,
  val regionName: String?,
)

@Composable
private fun VoiceSectionHeader(label: String) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.sp,
    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
  )
}

private sealed interface VoiceRowStatus {
  data object Idle : VoiceRowStatus

  data class Active(val progress: Float) : VoiceRowStatus

  data object Queued : VoiceRowStatus
}

@Composable
private fun VoiceRow(
  voice: VoiceWithRegion,
  isInstalled: Boolean,
  isDefault: Boolean,
  dimmed: Boolean,
  player: SamplePlayer,
  onTap: (() -> Unit)?,
  onAction: () -> Unit,
  downloadStatus: VoiceRowStatus,
) {
  val pack = voice.pack
  val baseModifier = Modifier.fillMaxWidth()
  val rowModifier =
    if (onTap != null && !isDefault) {
      baseModifier.clickable(onClick = onTap)
    } else {
      baseModifier
    }
  val nameColor =
    if (dimmed) {
      MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    } else {
      MaterialTheme.colorScheme.onSurface
    }
  val metaColor =
    if (dimmed) {
      MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    } else {
      MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }
  val iconTint =
    if (dimmed) {
      MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    } else {
      LocalContentColor.current
    }
  val nameWeight = if (isInstalled) FontWeight.Bold else FontWeight.Normal
  Row(
    modifier = rowModifier.padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      if (isDefault) {
        Text(
          text = stringResource(R.string.langmgr_default),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 1.sp,
        )
      }
      Text(
        text = pack.displayName,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = nameWeight,
        color = nameColor,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      )
      val speakerCount = pack.speakerCount.toInt()
      val speakerCountLabel = pluralStringResource(R.plurals.langmgr_voice_speaker_count, speakerCount, speakerCount)
      val parts =
        buildList {
          add(formatQualityLabel(pack.quality).replaceFirstChar { it.uppercase() })
          add(formatSize((if (isInstalled) pack.sizeBytes else pack.downloadSizeBytes).toLong()))
          if (speakerCount > 1) {
            add(speakerCountLabel)
          }
          voice.regionName?.let { add(it) }
        }
      Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = metaColor,
      )
    }
    Row(
      modifier = Modifier.width(88.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.End,
    ) {
      SampleButton(
        packId = pack.packId,
        sampleUrl = pack.sampleUrl,
        player = player,
        tint = iconTint,
      )
      val actionIcon = if (isInstalled) R.drawable.delete else R.drawable.download
      val actionDescription = if (isInstalled) "Delete voice" else "Download voice"
      when (downloadStatus) {
        is VoiceRowStatus.Active -> {
          Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(
              progress = { downloadStatus.progress },
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = iconTint,
            )
          }
        }
        VoiceRowStatus.Queued -> {
          Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              strokeWidth = 2.dp,
              color = iconTint.copy(alpha = 0.4f),
            )
          }
        }
        VoiceRowStatus.Idle -> {
          IconButton(
            onClick = onAction,
            modifier = Modifier.size(40.dp),
          ) {
            Icon(
              painter = painterResource(id = actionIcon),
              contentDescription = actionDescription,
              modifier = Modifier.size(20.dp),
              tint = iconTint,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SampleButton(
  packId: String,
  sampleUrl: String?,
  player: SamplePlayer,
  tint: Color = LocalContentColor.current,
) {
  val size = 40.dp
  if (sampleUrl == null) {
    Spacer(modifier = Modifier.size(size))
    return
  }
  val state = player.state
  val isLoading = state is SamplePlaybackState.Loading && state.packId == packId
  val isPlaying = state is SamplePlaybackState.Playing && state.packId == packId
  IconButton(
    onClick = { player.toggle(packId, sampleUrl) },
    modifier = Modifier.size(size),
  ) {
    if (isLoading) {
      CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = tint,
      )
    } else {
      val iconRes = if (isPlaying) R.drawable.stop else R.drawable.volume_up
      val description = if (isPlaying) "Stop sample" else "Play sample"
      Icon(
        painter = painterResource(id = iconRes),
        contentDescription = description,
        modifier = Modifier.size(20.dp),
        tint = tint,
      )
    }
  }
}

@Composable
private fun FavoriteButton(
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
    modifier = Modifier.size(AppLayout.dimens.assetRowButtonSize),
  ) {
    Icon(
      painter = painterResource(id = if (isFavorite) R.drawable.star_filled else R.drawable.star),
      contentDescription = if (isFavorite) stringResource(R.string.a11y_favorite_remove) else stringResource(R.string.a11y_favorite_add),
      tint = if (isFavorite) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      modifier = Modifier.size(18.dp),
    )
  }
}

private data class LanguageFeatureRow(
  @androidx.annotation.StringRes val labelRes: Int,
  val secondaryLabel: String? = null,
  val installed: Boolean,
  val downloadStatus: DownloadStatus?,
  val onDownload: () -> Unit,
  val onInstalledAction: () -> Unit,
  val onCancel: () -> Unit,
  @androidx.annotation.DrawableRes val installedIconRes: Int = R.drawable.delete,
  val installedDescription: String = "Delete",
  val actionTag: String? = null,
)

@Immutable
private data class LanguageAssetRow(
  val language: Language,
  val availability: LangAvailability,
  val dictionaryInfo: DictionaryInfo?,
  val translationVisible: Boolean,
  val dictionaryVisible: Boolean,
  val ttsVisible: Boolean,
  val translationSizeBytes: Long,
  val ttsSizeBytes: Long,
) {
  val translationInstalled: Boolean get() = translationVisible && availability.translatorFiles
  val dictionaryInstalled: Boolean get() = dictionaryVisible && availability.dictionaryFiles
  val ttsInstalled: Boolean get() = ttsVisible && availability.ttsFiles
  val visibleFeatureCount: Int get() = listOf(translationVisible, dictionaryVisible, ttsVisible).count { it }
  val installedFeatureCount: Int get() = listOf(translationInstalled, dictionaryInstalled, ttsInstalled).count { it }
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
  settingsManager: SettingsManager,
  downloadManager: DownloadManager,
  listHeader: (@Composable () -> Unit)? = null,
) {
  val dimens = AppLayout.dimens
  val catalog by languageStateManager.catalog.collectAsState()
  val languageMetadata by languageMetadataManager.metadata.collectAsState()
  val downloads by downloadManager.state.collectAsState()
  val downloadErrors = downloads.values.filterIsInstance<DownloadStatus.Failed>().map { it.reason }.distinct()
  val expandedLanguages = remember { mutableStateMapOf<String, Boolean>() }
  var filterQuery by remember { mutableStateOf("") }
  var pendingSharedDictionaryDelete by remember { mutableStateOf<PendingSharedDictionaryDelete?>(null) }
  var pendingTtsVoicePicker by remember { mutableStateOf<PendingTtsVoicePicker?>(null) }

  // Re-check the filesystem on entry: install state can drift without a
  // download event (files lost to storage issues, a newly fetched catalog
  // orphaning an on-disk variant), and this screen is where that surfaces
  // as the repair banner.
  LaunchedEffect(Unit) {
    languageStateManager.refreshLanguageAvailability()
  }

  val normalizedFilter = filterQuery.trim().lowercase()
  val rows =
    remember(catalog, normalizedFilter) {
      catalog.languageRows
        .sortedWith(compareBy(languageNameComparator()) { it.language.localizedName() })
        .mapNotNull { entry ->
          val language = entry.language
          val availability = entry.availability
          val dictInfo = catalog.dictionaryInfoFor(language)
          val translationVisible = !language.isEnglish
          val dictionaryVisible = dictInfo != null
          val ttsVisible = catalog.hasTtsVoices(language.code)
          if (!translationVisible && !dictionaryVisible && !ttsVisible) {
            null
          } else {
            LanguageAssetRow(
              language = language,
              availability = availability,
              dictionaryInfo = dictInfo,
              translationVisible = translationVisible,
              dictionaryVisible = dictionaryVisible,
              ttsVisible = ttsVisible,
              translationSizeBytes = catalog.sizeBytesForFeature(language.code, Feature.CORE),
              ttsSizeBytes = catalog.sizeBytesForFeature(language.code, Feature.TTS),
            )
          }
        }.filter { row ->
          if (normalizedFilter.isBlank()) {
            true
          } else {
            val language = row.language
            val haystack =
              listOf(language.localizedName(), language.displayName, language.shortDisplayName, language.code)
                .joinToString(" ")
                .lowercase()
            normalizedFilter in haystack
          }
        }
    }
  // Packs with files on disk but missing required ones (partial download, or a
  // catalog change that orphaned the installed variant): without a repair these
  // read as not-installed and their feature silently stops working (issue #246).
  val repairPlanSize =
    remember(catalog) {
      catalog.planRepair().takeIf { it.tasks.isNotEmpty() }?.totalSize?.toLong() ?: 0L
    }

  val ppocrModelUpgrade =
    remember(catalog, rows) {
      val languages =
        rows
          .filter { PPOCR_ENGINE in catalog.installedOcrEngines(it.language.code) }
          .map { it.language }
      val plan = catalog.planOcrEngineUpgrades(languages.map { it.code }, PPOCR_ENGINE)
      if (plan.tasks.isEmpty()) {
        PaddleUpgrade.EMPTY
      } else {
        PaddleUpgrade(languages = languages, totalBytes = plan.totalSize.toLong())
      }
    }

  val translationUpgrade =
    remember(catalog, rows) {
      val codes = catalog.translationUpgradeLanguageCodes().toSet()
      val languages = rows.map { it.language }.filter { it.code in codes }
      val plan = catalog.planTranslationUpgrades(languages.map { it.code })
      if (plan.tasks.isEmpty()) {
        TranslationUpgrade.EMPTY
      } else {
        TranslationUpgrade(languages = languages, totalBytes = plan.totalSize.toLong())
      }
    }

  val ttsUpgrade =
    remember(catalog, rows) {
      val codes = catalog.ttsUpgradeLanguageCodes().toSet()
      val languages = rows.map { it.language }.filter { it.code in codes }
      val plan = catalog.planTtsUpgrades(languages.map { it.code })
      if (plan.tasks.isEmpty()) {
        TtsUpgrade.EMPTY
      } else {
        TtsUpgrade(languages = languages, totalBytes = plan.totalSize.toLong())
      }
    }

  val sharedDictionaryUsersByLanguageCode =
    remember(rows) {
      rows
        .asSequence()
        .filter { it.dictionaryInstalled }
        .groupBy { it.language.dictionaryCode }
        .mapValues { (_, installedRows) ->
          installedRows
            .map { it.language }
            .sortedBy { it.displayName }
        }.flatMap { (_, languages) ->
          languages.map { language ->
            language.code to languages.filter { it.code != language.code }
          }
        }.toMap()
    }

  PullToRefreshBox(
    isRefreshing = downloads[DownloadId.Catalog].inFlightProgress != null,
    onRefresh = { downloadManager.enqueue(DownloadRequest.Catalog) },
    modifier =
      Modifier
        .fillMaxSize()
        .imePadding(),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(horizontal = dimens.assetListHorizontalPadding),
    ) {
      LanguageFilterField(
        query = filterQuery,
        onQueryChange = { filterQuery = it },
      )

      if (repairPlanSize > 0L) {
        RepairCard(
          totalBytes = repairPlanSize,
          isDownloading = downloads[DownloadId.Repair].inFlightProgress != null,
          onRepair = { downloadManager.enqueue(DownloadRequest.Repair) },
        )
      }

      if (ppocrModelUpgrade.languages.isNotEmpty()) {
        UpgradeCard(
          text = stringResource(R.string.langmgr_ocr_upgrade_available),
          totalBytes = ppocrModelUpgrade.totalBytes,
          isDownloading = downloads[DownloadId.OcrUpgrade(PPOCR_ENGINE)].inFlightProgress != null,
          onDownload = {
            downloadManager.enqueue(DownloadRequest.OcrUpgrade(ppocrModelUpgrade.languages, PPOCR_ENGINE))
          },
        )
      }

      if (translationUpgrade.languages.isNotEmpty()) {
        UpgradeCard(
          text =
            stringResource(
              R.string.langmgr_translation_upgrade_available,
              translationUpgrade.languages.joinToString(", ") { it.displayName },
            ),
          totalBytes = translationUpgrade.totalBytes,
          isDownloading = downloads[DownloadId.TranslationUpgrade].inFlightProgress != null,
          onDownload = {
            downloadManager.enqueue(DownloadRequest.TranslationUpgrade(translationUpgrade.languages))
          },
        )
      }

      if (ttsUpgrade.languages.isNotEmpty()) {
        UpgradeCard(
          text =
            stringResource(
              R.string.langmgr_tts_upgrade_available,
              ttsUpgrade.languages.joinToString(", ") { it.displayName },
            ),
          totalBytes = ttsUpgrade.totalBytes,
          isDownloading = downloads[DownloadId.TtsUpgrade].inFlightProgress != null,
          onDownload = {
            downloadManager.enqueue(DownloadRequest.TtsUpgrade(ttsUpgrade.languages))
          },
        )
      }

      LazyColumn(
        modifier = Modifier.fillMaxSize(),
      ) {
        itemsIndexed(downloadErrors, key = { _, reason -> "download-error:$reason" }) { _, reason ->
          Text(
            text = reason,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
          )
        }
        if (listHeader != null) {
          item(key = "list-header") { listHeader() }
        }
        itemsIndexed(rows, key = { _, row -> row.language.code }) { index, row ->
          val expanded = expandedLanguages[row.language.code] == true
          val sharedDictionaryUsers = sharedDictionaryUsersByLanguageCode[row.language.code].orEmpty()
          LanguageAssetCard(
            row = row,
            zebra = index % 2 == 1,
            expanded = expanded,
            isFavorite = languageMetadata[row.language]?.favorite ?: false,
            translationDownloadStatus = downloads[DownloadId.Core(row.language)],
            dictionaryDownloadStatus = downloads[DownloadId.Dictionary(row.language)],
            ttsDownloadStatus = languageTtsStatus(downloads, row.language),
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
              downloadManager.enqueue(DownloadRequest.Core(row.language))
            },
            onDeleteTranslation = {
              languageStateManager.deleteLanguage(row.language)
            },
            onCancelTranslation = {
              downloadManager.cancel(DownloadId.Core(row.language))
            },
            onDownloadDictionary = {
              downloadManager.enqueue(DownloadRequest.Dictionary(row.language))
            },
            onDeleteDictionary = {
              if (sharedDictionaryUsers.isNotEmpty()) {
                pendingSharedDictionaryDelete =
                  PendingSharedDictionaryDelete(
                    language = row.language,
                    sharedWith = sharedDictionaryUsers,
                    deleteLanguage = false,
                    deleteTts = false,
                  )
              } else {
                languageStateManager.deleteDict(row.language)
              }
            },
            onCancelDictionary = {
              downloadManager.cancel(DownloadId.Dictionary(row.language))
            },
            onDownloadTts = {
              pendingTtsVoicePicker = PendingTtsVoicePicker(row.language)
            },
            onCancelTts = {
              languageTtsDownloads(downloads, row.language).keys.forEach(downloadManager::cancel)
            },
            onDownloadAll = {
              if (row.translationVisible && !row.translationInstalled) {
                downloadManager.enqueue(DownloadRequest.Core(row.language))
              }
              if (row.dictionaryVisible && !row.dictionaryInstalled) {
                downloadManager.enqueue(DownloadRequest.Dictionary(row.language))
              }
              if (row.ttsVisible && !row.ttsInstalled) {
                val packId =
                  checkNotNull(catalog.defaultTtsPackIdForLanguage(row.language.code)) {
                    "${row.language.code} shows TTS but has no default voice pack"
                  }
                downloadManager.enqueue(DownloadRequest.Tts(row.language, packId))
              }
            },
            onDeleteAll = {
              if (row.dictionaryInstalled && sharedDictionaryUsers.isNotEmpty()) {
                pendingSharedDictionaryDelete =
                  PendingSharedDictionaryDelete(
                    language = row.language,
                    sharedWith = sharedDictionaryUsers,
                    deleteLanguage = row.translationInstalled,
                    deleteTts = row.ttsInstalled,
                  )
              } else {
                if (row.translationInstalled) {
                  languageStateManager.deleteLanguage(row.language)
                }
                if (row.ttsInstalled) {
                  languageStateManager.deleteTts(row.language)
                }
                if (row.dictionaryInstalled) {
                  languageStateManager.deleteDict(row.language)
                }
              }
            },
            onCancelAll = {
              downloadManager.cancel(DownloadId.Core(row.language))
              downloadManager.cancel(DownloadId.Dictionary(row.language))
              languageTtsDownloads(downloads, row.language).keys.forEach(downloadManager::cancel)
            },
          )
        }
      }
    }
  }
  pendingSharedDictionaryDelete?.let { pendingDelete ->
    val sharedNames = pendingDelete.sharedWith.joinToString(", ") { it.displayName }
    AlertDialog(
      onDismissRequest = { pendingSharedDictionaryDelete = null },
      title = { Text(stringResource(R.string.langmgr_delete_shared_title)) },
      text = {
        Text(
          stringResource(R.string.langmgr_delete_shared_body, sharedNames),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            if (pendingDelete.deleteLanguage) {
              languageStateManager.deleteLanguage(pendingDelete.language)
            }
            if (pendingDelete.deleteTts) {
              languageStateManager.deleteTts(pendingDelete.language)
            }
            languageStateManager.deleteDict(pendingDelete.language)
            pendingSharedDictionaryDelete = null
          },
        ) {
          Text(stringResource(R.string.common_ok))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { pendingSharedDictionaryDelete = null },
        ) {
          Text(stringResource(R.string.common_cancel))
        }
      },
    )
  }

  pendingTtsVoicePicker?.let { pendingPicker ->
    VoicePickerDialog(
      context = context,
      language = pendingPicker.language,
      catalog = catalog,
      languageStateManager = languageStateManager,
      settingsManager = settingsManager,
      downloads = downloads,
      onDownloadVoice = { packId -> downloadManager.enqueue(DownloadRequest.Tts(pendingPicker.language, packId)) },
      onDismiss = { pendingTtsVoicePicker = null },
    )
  }
}

// The regular outlined field's 56 dp minimum and floating label would eat a
// third of a tiny screen, so compact draws the same outline around a short
// single line with a placeholder instead.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageFilterField(
  query: String,
  onQueryChange: (String) -> Unit,
) {
  val label = stringResource(R.string.langmgr_filter)
  when (AppLayout.density) {
    LayoutDensity.Regular ->
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 6.dp),
        singleLine = true,
        label = { Text(label) },
      )

    LayoutDensity.Compact -> {
      val interactionSource = remember { MutableInteractionSource() }
      val textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp),
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
      ) { innerTextField ->
        OutlinedTextFieldDefaults.DecorationBox(
          value = query,
          innerTextField = innerTextField,
          enabled = true,
          singleLine = true,
          visualTransformation = VisualTransformation.None,
          interactionSource = interactionSource,
          placeholder = { Text(label, style = textStyle) },
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        )
      }
    }
  }
}

@Composable
private fun LanguageAssetCard(
  row: LanguageAssetRow,
  zebra: Boolean,
  expanded: Boolean,
  isFavorite: Boolean,
  translationDownloadStatus: DownloadStatus?,
  dictionaryDownloadStatus: DownloadStatus?,
  ttsDownloadStatus: DownloadStatus?,
  onToggleExpanded: () -> Unit,
  onFavorite: (FavoriteEvent) -> Unit,
  onDownloadTranslation: () -> Unit,
  onDeleteTranslation: () -> Unit,
  onCancelTranslation: () -> Unit,
  onDownloadDictionary: () -> Unit,
  onDeleteDictionary: () -> Unit,
  onCancelDictionary: () -> Unit,
  onDownloadTts: () -> Unit,
  onCancelTts: () -> Unit,
  onDownloadAll: () -> Unit,
  onDeleteAll: () -> Unit,
  onCancelAll: () -> Unit,
) {
  val totalVisibleSize =
    (if (row.translationVisible) row.translationSizeBytes else 0L) +
      (if (row.dictionaryVisible) row.dictionaryInfo?.size ?: 0L else 0L) +
      (if (row.ttsVisible) row.ttsSizeBytes else 0L)
  val collapsedDownloadStatus =
    when {
      row.fullyMissing -> translationDownloadStatus ?: dictionaryDownloadStatus ?: ttsDownloadStatus
      row.fullyInstalled -> null
      else -> null
    }
  val dimens = AppLayout.dimens
  val clusterToStarSpacing =
    if (expanded || row.partiallyInstalled) {
      8.dp
    } else {
      2.dp
    }
  val expandLabel = if (expanded) stringResource(R.string.a11y_collapse) else stringResource(R.string.a11y_expand)

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .background(
          if (zebra) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
          } else {
            Color.Transparent
          },
        )
        .padding(vertical = 1.dp),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable(onClickLabel = expandLabel) { onToggleExpanded() }
          .padding(vertical = 1.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // The whole row already toggles, so a tiny screen drops the chevron and
      // only indents the name to keep it off the edge.
      when (AppLayout.density) {
        LayoutDensity.Regular ->
          IconButton(
            onClick = onToggleExpanded,
            modifier = Modifier.size(28.dp),
          ) {
            Icon(
              painter = painterResource(id = if (expanded) R.drawable.expandless else R.drawable.expandmore),
              contentDescription = expandLabel,
              modifier = Modifier.size(18.dp),
            )
          }

        LayoutDensity.Compact -> Spacer(modifier = Modifier.width(dimens.assetRowIndent))
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(0.dp),
      ) {
        Text(
          text = row.language.localizedName(),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = formatSize(totalVisibleSize),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
      ) {
        Box(
          modifier = Modifier.width(dimens.assetStatusClusterWidth),
          contentAlignment = Alignment.CenterEnd,
        ) {
          when {
            expanded || row.partiallyInstalled -> {
              FeaturePresenceIndicators(row)
            }

            else -> {
              AggregateActionButton(
                downloadStatus = collapsedDownloadStatus,
                isInstalled = row.fullyInstalled,
                onDownload = onDownloadAll,
                onDelete = onDeleteAll,
                onCancel = onCancelAll,
              )
            }
          }
        }

        Spacer(modifier = Modifier.width(clusterToStarSpacing))

        FavoriteButton(
          isFavorite = isFavorite,
          language = row.language,
          onEvent = onFavorite,
        )
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
            .padding(start = dimens.assetRowIndent, end = 0.dp, bottom = 1.dp),
      ) {
        val featureRows =
          buildFeatureRows(
            row = row,
            translationDownloadStatus = translationDownloadStatus,
            dictionaryDownloadStatus = dictionaryDownloadStatus,
            ttsDownloadStatus = ttsDownloadStatus,
            onDownloadTranslation = onDownloadTranslation,
            onDeleteTranslation = onDeleteTranslation,
            onCancelTranslation = onCancelTranslation,
            onDownloadDictionary = onDownloadDictionary,
            onDeleteDictionary = onDeleteDictionary,
            onCancelDictionary = onCancelDictionary,
            onDownloadTts = onDownloadTts,
            onCancelTts = onCancelTts,
          )
        featureRows.forEach { featureRow ->
          FeatureRow(featureRow)
        }
      }
    }
  }
}

private fun buildFeatureRows(
  row: LanguageAssetRow,
  translationDownloadStatus: DownloadStatus?,
  dictionaryDownloadStatus: DownloadStatus?,
  ttsDownloadStatus: DownloadStatus?,
  onDownloadTranslation: () -> Unit,
  onDeleteTranslation: () -> Unit,
  onCancelTranslation: () -> Unit,
  onDownloadDictionary: () -> Unit,
  onDeleteDictionary: () -> Unit,
  onCancelDictionary: () -> Unit,
  onDownloadTts: () -> Unit,
  onCancelTts: () -> Unit,
): List<LanguageFeatureRow> {
  val featureRows = mutableListOf<LanguageFeatureRow>()

  if (row.translationVisible) {
    featureRows +=
      LanguageFeatureRow(
        labelRes = R.string.feature_translation,
        secondaryLabel = formatSize(row.translationSizeBytes),
        installed = row.translationInstalled,
        downloadStatus = translationDownloadStatus,
        onDownload = onDownloadTranslation,
        onInstalledAction = onDeleteTranslation,
        onCancel = onCancelTranslation,
      )
  }

  if (row.dictionaryVisible) {
    featureRows +=
      LanguageFeatureRow(
        labelRes = R.string.feature_dictionary,
        secondaryLabel =
          buildDictionarySecondaryLabel(
            sizeBytes = row.dictionaryInfo?.size ?: 0L,
            type = row.dictionaryInfo?.type,
          ),
        installed = row.dictionaryInstalled,
        downloadStatus = dictionaryDownloadStatus,
        onDownload = onDownloadDictionary,
        onInstalledAction = onDeleteDictionary,
        onCancel = onCancelDictionary,
      )
  }

  if (row.ttsVisible) {
    featureRows +=
      LanguageFeatureRow(
        labelRes = R.string.feature_tts,
        secondaryLabel = formatSize(row.ttsSizeBytes),
        installed = row.ttsInstalled,
        downloadStatus = ttsDownloadStatus,
        onDownload = onDownloadTts,
        onInstalledAction = onDownloadTts,
        onCancel = onCancelTts,
        installedIconRes = R.drawable.settings,
        installedDescription = "Manage voices",
        actionTag = "export-trigger:voice",
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
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = stringResource(featureRow.labelRes),
        style = MaterialTheme.typography.bodyMedium,
      )
      featureRow.secondaryLabel?.let { secondaryLabel ->
        Text(
          text = secondaryLabel,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
      }
    }
    FeatureActionButton(
      downloadStatus = featureRow.downloadStatus,
      isInstalled = featureRow.installed,
      onDownload = featureRow.onDownload,
      onInstalledAction = featureRow.onInstalledAction,
      onCancel = featureRow.onCancel,
      installedIconRes = featureRow.installedIconRes,
      installedDescription = featureRow.installedDescription,
      actionTag = featureRow.actionTag,
    )
  }
}

// Non-translatable single-letter glyph marking the translation feature.
private const val TRANSLATION_FEATURE_BADGE = "T"

@Composable
private fun FeaturePresenceIndicators(row: LanguageAssetRow) {
  val installedTint = MaterialTheme.colorScheme.onSurface
  val missingTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(AppLayout.dimens.assetStatusIndicatorSpacing),
  ) {
    if (row.translationVisible) {
      Text(
        text = TRANSLATION_FEATURE_BADGE,
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
        contentDescription = stringResource(R.string.a11y_dictionary_status),
        tint =
          if (row.dictionaryInstalled) {
            installedTint
          } else {
            missingTint
          },
        modifier = Modifier.size(20.dp),
      )
    }

    if (row.ttsVisible) {
      Icon(
        painter = painterResource(id = R.drawable.volume_up),
        contentDescription = stringResource(R.string.a11y_tts_status),
        tint =
          if (row.ttsInstalled) {
            installedTint
          } else {
            missingTint
          },
        modifier =
          Modifier
            .size(22.dp)
            .offset { IntOffset(1, 0) },
      )
    }
  }
}

@Composable
private fun AggregateActionButton(
  downloadStatus: DownloadStatus?,
  isInstalled: Boolean,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
  onCancel: () -> Unit,
) {
  val inFlightProgress = downloadStatus.inFlightProgress
  if (inFlightProgress != null) {
    ProgressIconButton(
      progress = inFlightProgress,
      onClick = onCancel,
      contentDescription = stringResource(R.string.a11y_cancel_download),
    )
    return
  }

  IconButton(
    onClick = if (isInstalled) onDelete else onDownload,
    modifier = Modifier.size(AppLayout.dimens.assetRowButtonSize),
  ) {
    Icon(
      painter =
        painterResource(
          id =
            when {
              isInstalled -> R.drawable.delete
              downloadStatus is DownloadStatus.Failed -> R.drawable.refresh
              else -> R.drawable.add
            },
        ),
      contentDescription =
        when {
          isInstalled -> stringResource(R.string.a11y_delete)
          downloadStatus is DownloadStatus.Failed -> stringResource(R.string.a11y_retry_download)
          else -> stringResource(R.string.a11y_download)
        },
      modifier = Modifier.size(18.dp),
    )
  }
}

@Composable
private fun FeatureActionButton(
  downloadStatus: DownloadStatus?,
  isInstalled: Boolean,
  onDownload: () -> Unit,
  onInstalledAction: () -> Unit,
  onCancel: () -> Unit,
  @androidx.annotation.DrawableRes installedIconRes: Int,
  installedDescription: String,
  actionTag: String? = null,
) {
  val inFlightProgress = downloadStatus.inFlightProgress
  if (inFlightProgress != null) {
    ProgressIconButton(
      progress = inFlightProgress,
      onClick = onCancel,
      contentDescription = stringResource(R.string.a11y_cancel_download),
    )
    return
  }

  IconButton(
    onClick = if (isInstalled) onInstalledAction else onDownload,
    modifier =
      Modifier
        .size(AppLayout.dimens.assetRowButtonSize)
        .then(if (actionTag != null) Modifier.testTag(actionTag) else Modifier),
  ) {
    Icon(
      painter =
        painterResource(
          id =
            when {
              isInstalled -> installedIconRes
              downloadStatus is DownloadStatus.Failed -> R.drawable.refresh
              else -> R.drawable.add
            },
        ),
      contentDescription =
        when {
          isInstalled -> installedDescription
          downloadStatus is DownloadStatus.Failed -> stringResource(R.string.a11y_retry_download)
          else -> stringResource(R.string.a11y_download_feature)
        },
      modifier = Modifier.size(18.dp),
    )
  }
}

@Composable
private fun ProgressIconButton(
  progress: Float,
  onClick: () -> Unit,
  contentDescription: String,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier.size(32.dp),
  ) {
    val animatedProgress by animateFloatAsState(
      targetValue = progress,
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

@Composable
private fun RepairCard(
  totalBytes: Long,
  isDownloading: Boolean,
  onRepair: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer,
    shape = RoundedCornerShape(12.dp),
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(bottom = 6.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.langmgr_repair_needed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.weight(1f),
      )
      if (isDownloading) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
      } else {
        TextButton(onClick = onRepair) {
          Text(stringResource(R.string.langmgr_repair_size, formatSize(totalBytes)))
        }
      }
    }
  }
}

@Composable
private fun UpgradeCard(
  text: String,
  totalBytes: Long,
  isDownloading: Boolean,
  onDownload: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(12.dp),
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(bottom = 6.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f),
      )
      if (isDownloading) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
      } else {
        TextButton(onClick = onDownload) {
          Text(stringResource(R.string.langmgr_download_size, formatSize(totalBytes)))
        }
      }
    }
  }
}

private fun formatSize(sizeBytes: Long): String {
  val sizeMiB = sizeBytes / (1024f * 1024f)
  return if (sizeMiB < 1f) {
    "<1 MB"
  } else {
    "${sizeMiB.roundToInt()} MB"
  }
}

private fun buildDictionarySecondaryLabel(
  sizeBytes: Long,
  type: String?,
): String? {
  val parts = mutableListOf(formatSize(sizeBytes))
  dictionaryTypeLabel(type)?.let(parts::add)
  return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun dictionaryTypeLabel(type: String?): String? =
  when (type?.lowercase()) {
    null, "" -> null
    "english" -> "English"
    "bilingual" -> "Bilingual"
    else -> type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
  }

private fun formatQualityLabel(quality: String?): String =
  when (quality?.lowercase()) {
    "x_low" -> "Extra-low"
    null, "" -> "Unknown"
    else -> quality.replace('_', '-').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
  }

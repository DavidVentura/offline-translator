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

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import dev.davidv.translator.AppSettings
import dev.davidv.translator.DownloadId
import dev.davidv.translator.DownloadRequest
import dev.davidv.translator.DownloadStatus
import dev.davidv.translator.InputSplit
import dev.davidv.translator.LangAvailability
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityEntry
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.LaunchMode
import dev.davidv.translator.R
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.Script
import dev.davidv.translator.TranslatedText
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.localizedName
import dev.davidv.translator.singleScript
import dev.davidv.translator.ui.DictionaryState
import dev.davidv.translator.ui.SpeechState
import dev.davidv.translator.ui.SpeechTarget
import dev.davidv.translator.ui.components.ActionPillButton
import dev.davidv.translator.ui.components.AlternativesDrawer
import dev.davidv.translator.ui.components.AlternativesTarget
import dev.davidv.translator.ui.components.ClearInput
import dev.davidv.translator.ui.components.DetectedLanguageSection
import dev.davidv.translator.ui.components.DetectedRegions
import dev.davidv.translator.ui.components.DictionaryBottomSheet
import dev.davidv.translator.ui.components.ImageDisplaySection
import dev.davidv.translator.ui.components.ImageWordSelection
import dev.davidv.translator.ui.components.InputSection
import dev.davidv.translator.ui.components.JapaneseOcrModeToggle
import dev.davidv.translator.ui.components.LanguageEvent
import dev.davidv.translator.ui.components.LanguageSelectionRow
import dev.davidv.translator.ui.components.OutputTapMode
import dev.davidv.translator.ui.components.ShareImage
import dev.davidv.translator.ui.components.SplitHandle
import dev.davidv.translator.ui.components.StyledTextFieldFocusController
import dev.davidv.translator.ui.components.TargetTabsHeader
import dev.davidv.translator.ui.components.TranslationField
import dev.davidv.translator.ui.components.ZoomableImageViewer
import dev.davidv.translator.ui.components.rememberImageSourceActions
import dev.davidv.translator.ui.components.wordAt
import dev.davidv.translator.ui.components.wordRangeAt
import dev.davidv.translator.ui.theme.AppLayout
import dev.davidv.translator.ui.theme.LayoutDensity
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.roundToInt

@Composable
fun MainScreen(
  // Navigation
  onSettings: () -> Unit,
  onLiveCamera: () -> Unit,
  // Current state (read-only)
  input: String,
  inputTransliteration: String?,
  output: TranslatedText?,
  from: Language,
  to: Language,
  targetTabs: List<Language> = listOf(to),
  detectedLanguage: Language?,
  displayImage: Bitmap?,
  originalImage: Bitmap?,
  imageWordSelection: ImageWordSelection?,
  detectedRegions: DetectedRegions?,
  ocrReadingOrder: ReadingOrder?,
  isImageProcessing: Boolean,
  dictionary: DictionaryState,
  speech: SpeechState = SpeechState.Idle,
  // Action requests
  onMessage: (TranslatorMessage) -> Unit,
  onSteerPreview: suspend (String) -> String? = { null },
  canSwapLanguages: Boolean = true,
  onStopAudio: () -> Unit = {},
  // System integration
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  downloads: Map<DownloadId, DownloadStatus>,
  onDownload: (DownloadRequest) -> Unit,
  onCancelDownload: (DownloadId) -> Unit,
  settings: AppSettings,
  availableSourceTtsVoices: List<uniffi.translator_core.InstalledTtsPack> = emptyList(),
  selectedSourceTtsVoiceName: String? = null,
  sourceTtsPlaybackSpeed: Float = 1.0f,
  availableTtsVoices: List<uniffi.translator_core.InstalledTtsPack> = emptyList(),
  selectedTtsVoiceName: String? = null,
  targetTtsPlaybackSpeed: Float = 1.0f,
  onTtsPlaybackSpeedChange: (Float) -> Unit = {},
  onInputSplitChange: (InputSplit) -> Unit = {},
  onSourceTtsPlaybackSpeedChange: (Float) -> Unit = {},
  onSourceTtsVoiceSelected: (String, String) -> Unit = { _, _ -> },
  onTtsVoiceSelected: (String, String) -> Unit = { _, _ -> },
  onSpeakInput: (String, Language) -> Unit = { _, _ -> },
  onSpeakOutput: (String, Language) -> Unit = { _, _ -> },
  launchMode: LaunchMode,
  pendingSharedImage: SharedFlow<android.net.Uri>? = null,
  isAutoSource: Boolean = false,
) {
  var showFullScreenImage by remember { mutableStateOf(false) }
  var alternativesTarget by remember { mutableStateOf<AlternativesTarget?>(null) }
  var outputTapMode by remember { mutableStateOf(OutputTapMode.None) }
  var inputDictMode by remember { mutableStateOf(false) }
  val hasAnyAlternatives = output?.alternatives?.isNotEmpty() == true
  val hasSourceDictionary = languageState.availabilityFor(from)?.dictionaryFiles == true
  val hasTargetDictionary = languageState.availabilityFor(to)?.dictionaryFiles == true
  val canInputDict = hasSourceDictionary && input.isNotBlank()
  // A mode whose toggle is no longer offered would strand as an invisible tap
  // handler, so drop it when its backing data goes away.
  LaunchedEffect(hasAnyAlternatives) {
    if (!hasAnyAlternatives && outputTapMode == OutputTapMode.Alternatives) {
      outputTapMode = OutputTapMode.None
      alternativesTarget = null
    }
  }
  LaunchedEffect(hasTargetDictionary) {
    if (!hasTargetDictionary && outputTapMode == OutputTapMode.Dictionary) {
      outputTapMode = OutputTapMode.None
    }
  }
  LaunchedEffect(canInputDict) {
    if (!canInputDict) inputDictMode = false
  }
  // Flip the main image between the translation and the original (resets on a new image).
  var showOriginal by remember(displayImage) { mutableStateOf(false) }
  val inputFocusController = remember { StyledTextFieldFocusController() }
  val sourceActions =
    rememberImageSourceActions(
      onMessage = onMessage,
      from = from,
      to = to,
      pendingSharedImage = pendingSharedImage,
    )
  val extraTopPadding = if (launchMode == LaunchMode.Normal) 0.dp else 8.dp
  val context = LocalContext.current
  val showOnlyOutputInReadonlyModal =
    launchMode == LaunchMode.ReadonlyModal && settings.onlyShowOutputOnReadonlyModal
  val detectedInstalled =
    detectedLanguage?.takeIf(languageState::canBeSource)
  val mainScreenDescription = stringResource(R.string.a11y_main_screen)
  val layoutDensity = AppLayout.density
  val dimens = layoutDensity.dimens

  // Handle back button when dictionary is open
  BackHandler(enabled = dictionary is DictionaryState.Open) {
    onMessage(TranslatorMessage.ClearDictionaryStack)
  }

  Scaffold(
    modifier = Modifier.semantics { contentDescription = mainScreenDescription },
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    floatingActionButton = {
      when (launchMode) {
        LaunchMode.Normal -> {
        }

        LaunchMode.ReadonlyModal -> {
        }

        is LaunchMode.ReadWriteModal -> {
          if (output != null) {
            FloatingActionButton(
              onClick = {
                launchMode.reply(output.translated)
              },
              shape = FloatingActionButtonDefaults.smallShape,
              modifier = Modifier.size(30.dp),
            ) {
              Icon(
                painterResource(id = R.drawable.check),
                contentDescription = stringResource(R.string.a11y_replace_text),
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      }
    },
  ) { paddingValues ->
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .navigationBarsPadding()
          .padding(top = paddingValues.calculateTopPadding() + extraTopPadding, bottom = dimens.screenBottomPadding),
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(horizontal = dimens.screenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.sectionSpacing),
      ) {
        Box(modifier = Modifier.testTag("export-section:Language selection")) {
          LanguageSelectionRow(
            from = from,
            to = to,
            canSwap = canSwapLanguages,
            languageState = languageState,
            languageMetadata = languageMetadata,
            onMessage = onMessage,
            isAutoSource = isAutoSource,
            detectedInstalled = detectedInstalled,
            showAutoOption = !settings.disableCLD,
            drawable =
              if (launchMode == LaunchMode.Normal) {
                Pair("Settings", R.drawable.settings)
              } else {
                Pair(
                  "Expand",
                  R.drawable.open_in_full,
                )
              },
            onSettings =
              if (launchMode == LaunchMode.Normal) {
                onSettings
              } else {
                { onMessage(TranslatorMessage.ChangeLaunchMode(LaunchMode.Normal)) }
              },
          )
        }

        BoxWithConstraints(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f),
        ) {
          val parentHeight = maxHeight
          val parentHeightPx = with(LocalDensity.current) { parentHeight.toPx() }
          // Only ever read from measure/drag lambdas: reading it during composition
          // would recompose both cards (and the interop EditText) on every drag frame.
          // Only the full screen remembers the split: popups are a fraction of the screen
          // tall, so a proportion picked for one doesn't carry over to the other.
          val splitFraction =
            rememberSaveable {
              mutableFloatStateOf(
                when (launchMode) {
                  LaunchMode.Normal -> settings.mainInputSplit.fraction
                  else -> InputSplit.DEFAULT.fraction
                },
              )
            }

          Column(
            modifier = Modifier.fillMaxWidth(),
          ) {
            // The detected-language prompt is an input modifier, so it lives at
            // the bottom of the input card; in image mode (no input card) it
            // sits directly under the image instead. A popup's input card is too
            // short to share with it, so popups draw it outside their bounds
            // (TranslatorApp) and none is drawn here.
            val detectedLanguageSection: (@Composable () -> Unit)? =
              if (launchMode == LaunchMode.Normal) {
                {
                  Box(modifier = Modifier.testTag("export-section:Detected language")) {
                    DetectedLanguageSection(
                      detectedLanguage = detectedLanguage,
                      from = from,
                      languageState = languageState,
                      onMessage = onMessage,
                      downloads = downloads,
                      isAutoSource = isAutoSource,
                      onEvent = { event ->
                        when (event) {
                          is LanguageEvent.Download -> onDownload(DownloadRequest.Core(event.language))
                          is LanguageEvent.Cancel -> onCancelDownload(DownloadId.Core(event.language))
                          else -> Log.e("MainScreen", "Got unexpected event: $event")
                        }
                      },
                    )
                  }
                }
              } else {
                null
              }

            if (displayImage != null) {
              Box(modifier = Modifier.fillMaxWidth()) {
                ImageDisplaySection(
                  displayImage = displayImage,
                  originalImage = originalImage,
                  showOriginal = showOriginal,
                  isProcessing = isImageProcessing,
                  detectedRegions = detectedRegions,
                  wordSelection = imageWordSelection,
                  maxHeight = parentHeight * 0.85f,
                  onDictionaryLookup = { word, language ->
                    onMessage(TranslatorMessage.DictionaryLookup(word, language))
                  },
                  modifier = Modifier.fillMaxWidth().testTag("export-section:Image"),
                )
                Row(
                  modifier =
                    Modifier
                      .align(Alignment.TopEnd)
                      .padding(8.dp),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  if (from.code == "ja") {
                    JapaneseOcrModeToggle(
                      readingOrder = ocrReadingOrder,
                      onMessage = onMessage,
                    )
                  }
                  if (originalImage != null) {
                    ActionPillButton(
                      iconRes = R.drawable.flip,
                      contentDescription =
                        if (showOriginal) {
                          stringResource(
                            R.string.a11y_show_translated_image,
                          )
                        } else {
                          stringResource(R.string.a11y_show_original_image)
                        },
                      showBackdrop = true,
                      onClick = { showOriginal = !showOriginal },
                    )
                  }
                  ShareImage(onMessage)
                  ClearInput(
                    onMessage = onMessage,
                    showBackdrop = true,
                  )
                }
              }
              detectedLanguageSection?.invoke()
            }

            if (!showOnlyOutputInReadonlyModal && displayImage == null) {
              val isOtherAudioActive = speech.target.let { it != null && it != SpeechTarget.INPUT }
              val canSpeakInput =
                input.isNotBlank() && languageState.availabilityFor(from)?.ttsFiles == true && !isOtherAudioActive
              InputSection(
                input = input,
                inputTransliteration = inputTransliteration,
                from = from,
                onMessage = onMessage,
                focusController = inputFocusController,
                fontFactor = settings.fontFactor,
                showTransliteration = settings.showTransliterationOnInput,
                canInputDict = canInputDict,
                inputDictMode = inputDictMode,
                onToggleInputDict = { inputDictMode = !inputDictMode },
                canSpeakInput = canSpeakInput,
                isInputAudioPlaying = speech == SpeechState.Playing(SpeechTarget.INPUT),
                isInputAudioLoading = speech == SpeechState.Loading(SpeechTarget.INPUT),
                sourceTtsPlaybackSpeed = sourceTtsPlaybackSpeed,
                selectedSourceTtsVoiceName = selectedSourceTtsVoiceName,
                availableSourceTtsVoices = availableSourceTtsVoices,
                onSourceTtsPlaybackSpeedChange = onSourceTtsPlaybackSpeedChange,
                onSourceTtsVoiceSelected = onSourceTtsVoiceSelected,
                onSpeakInput = onSpeakInput,
                onStopAudio = onStopAudio,
                showSourceRow = launchMode == LaunchMode.Normal,
                sourceActions = sourceActions,
                onCameraClick = onLiveCamera,
                screenTranslateEnabled = settings.experimentalScreenTranslate,
                isAutoSource = isAutoSource,
                detectedSection = detectedLanguageSection,
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                      val height =
                        (constraints.maxHeight * splitFraction.floatValue).roundToInt()
                      val placeable =
                        measurable.measure(
                          constraints.copy(minHeight = height, maxHeight = height),
                        )
                      layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .testTag("export-section:Input"),
              )
              // A tiny screen has no height to spare for a drag band, and its
              // keypad users can't drag anyway, so the split stays at half.
              when (layoutDensity) {
                LayoutDensity.Regular ->
                  SplitHandle(
                    onDrag = { delta ->
                      splitFraction.floatValue =
                        InputSplit(splitFraction.floatValue).dragged(delta / parentHeightPx).fraction
                    },
                    onDragStopped = {
                      if (launchMode == LaunchMode.Normal) {
                        onInputSplitChange(InputSplit(splitFraction.floatValue))
                      }
                    },
                  )

                LayoutDensity.Compact -> Spacer(modifier = Modifier.height(dimens.sectionSpacing))
              }
            }

            // With an image the translation is shown in the image itself, so hide the output text
            // field too (the input is already hidden above).
            if (showOnlyOutputInReadonlyModal || displayImage == null) {
              Box(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .testTag("export-section:Output")
                    .let { m ->
                      if (displayImage == null) {
                        m.weight(1f, fill = true)
                      } else {
                        m.height(parentHeight * 0.5f)
                      }
                    },
              ) {
                TranslationField(
                  text = output,
                  modifier = Modifier.fillMaxSize(),
                  label = to.localizedName(),
                  header =
                    if (settings.multiTargetEnabled) {
                      {
                        TargetTabsHeader(
                          tabs = targetTabs,
                          active = to,
                          candidates =
                            languageState.allLanguages().filter { x ->
                              x != from && x !in targetTabs &&
                                (languageState.availabilityFor(x)?.hasFromEnglish == true || x.isEnglish)
                            },
                          showAdd = true,
                          onSwitch = { onMessage(TranslatorMessage.ToLang(it)) },
                          onAdd = { onMessage(TranslatorMessage.AddTab(it)) },
                          onRemove = { onMessage(TranslatorMessage.RemoveTab(it)) },
                        )
                      }
                    } else {
                      null
                    },
                  textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                      fontSize = (MaterialTheme.typography.bodyLarge.fontSize * settings.fontFactor),
                      lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight * settings.fontFactor),
                    ),
                  onDictionaryLookup = {
                    onMessage(TranslatorMessage.DictionaryLookup(it, to))
                  },
                  onAlternatives =
                    if (output?.alternatives?.isNotEmpty() == true) {
                      { start, end ->
                        val wa =
                          output?.alternatives?.firstOrNull {
                            start < it.tgtEnd.toInt() && end > it.tgtBegin.toInt()
                          }
                        alternativesTarget =
                          wa?.let { AlternativesTarget(it.tgtBegin.toInt(), it.tgtEnd.toInt(), it.options) }
                      }
                    } else {
                      null
                    },
                  hasAlternatives =
                    if (output?.alternatives?.isNotEmpty() == true) {
                      { start, end ->
                        output?.alternatives?.any {
                          start < it.tgtEnd.toInt() && end > it.tgtBegin.toInt()
                        } == true
                      }
                    } else {
                      null
                    },
                  highlightRange =
                    alternativesTarget?.let { it.wordBegin until it.wordEnd },
                  tapMode = outputTapMode,
                  onToggleAlternativesMode = {
                    outputTapMode =
                      if (outputTapMode == OutputTapMode.Alternatives) {
                        OutputTapMode.None
                      } else {
                        OutputTapMode.Alternatives
                      }
                    alternativesTarget = null
                  },
                  onToggleDictionaryMode = {
                    outputTapMode =
                      if (outputTapMode == OutputTapMode.Dictionary) {
                        OutputTapMode.None
                      } else {
                        OutputTapMode.Dictionary
                      }
                    alternativesTarget = null
                  },
                  dictionaryAvailable = hasTargetDictionary,
                  onDictionaryUnavailable = {
                    Toast
                      .makeText(context, context.getString(R.string.feature_unavailable_dictionary), Toast.LENGTH_SHORT)
                      .show()
                  },
                  speakerAvailable = languageState.availabilityFor(to)?.ttsFiles == true,
                  onSpeakerUnavailable = {
                    Toast
                      .makeText(context, context.getString(R.string.feature_unavailable_tts), Toast.LENGTH_SHORT)
                      .show()
                  },
                  onWordTap = { offset ->
                    when (outputTapMode) {
                      OutputTapMode.Alternatives -> {
                        val text = output?.translated
                        val wa =
                          output?.alternatives?.firstOrNull {
                            offset >= it.tgtBegin.toInt() && offset < it.tgtEnd.toInt()
                          }
                        alternativesTarget =
                          when {
                            wa != null ->
                              AlternativesTarget(wa.tgtBegin.toInt(), wa.tgtEnd.toInt(), wa.options)

                            text != null ->
                              wordRangeAt(text, offset)?.let {
                                AlternativesTarget(it.first, it.last + 1, emptyList())
                              }

                            else -> null
                          }
                      }

                      OutputTapMode.Dictionary -> {
                        val word = output?.translated?.let { wordAt(it, offset) }
                        if (!word.isNullOrBlank()) {
                          onMessage(TranslatorMessage.DictionaryLookup(word, to))
                        }
                      }

                      OutputTapMode.None -> {}
                    }
                  },
                  isAudioPlaying = speech == SpeechState.Playing(SpeechTarget.OUTPUT),
                  isAudioLoading = speech == SpeechState.Loading(SpeechTarget.OUTPUT),
                  speechPlaybackSpeed = targetTtsPlaybackSpeed,
                  selectedVoiceName = selectedTtsVoiceName,
                  availableVoices = availableTtsVoices,
                  onSpeak = {
                    if (speech.target == SpeechTarget.OUTPUT) {
                      onStopAudio()
                    } else {
                      output?.translated?.takeIf { it.isNotBlank() }?.let { translatedText ->
                        onSpeakOutput(translatedText, to)
                      }
                    }
                  },
                  onSpeechPlaybackSpeedChange = onTtsPlaybackSpeedChange,
                  onVoiceSelected = onTtsVoiceSelected,
                )
              }
            }
          }
        }
      }
    }
  }

  // Full screen image viewer
  if (showFullScreenImage && displayImage != null && originalImage != null) {
    ZoomableImageViewer(
      bitmap = displayImage,
      originalBitmap = originalImage,
      wordSelection = imageWordSelection,
      onDismiss = { showFullScreenImage = false },
      onShare = {
        onMessage(TranslatorMessage.ShareTranslatedImage)
      },
      onDictionaryLookup = { word, language ->
        onMessage(TranslatorMessage.DictionaryLookup(word, language))
      },
    )
  }

  alternativesTarget?.let { target ->
    AlternativesDrawer(
      target = target,
      fullText = output?.translated.orEmpty(),
      steerPreview = onSteerPreview,
      onCommit = { prefix ->
        onMessage(TranslatorMessage.Steer(prefix))
        alternativesTarget = null
      },
      onDismiss = { alternativesTarget = null },
    )
  }

  if (dictionary is DictionaryState.Open) {
    DictionaryBottomSheet(
      dictionaryWord = dictionary.word,
      dictionaryStack = dictionary.stack,
      dictionaryLookupLanguage = dictionary.language,
      onDismiss = {
        onMessage(TranslatorMessage.ClearDictionaryStack)
      },
      onDictionaryLookup = { word ->
        onMessage(TranslatorMessage.DictionaryLookup(word, dictionary.language))
      },
      onBackPressed = {
        onMessage(TranslatorMessage.PopDictionary)
      },
    )
  }
}

@Composable
fun WideDialogTheme(content: @Composable () -> Unit) {
  TranslatorTheme {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(Color.Transparent),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier =
          Modifier
            .fillMaxWidth(0.9f)
            .height((LocalConfiguration.current.screenHeightDp * 0.5f).dp)
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
      ) {
        content()
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
  script = Script.LATIN,
  writingSystem = singleScript(Script.LATIN),
  dictionaryCode = code,
)

private fun previewLanguageState(vararg languages: Pair<Language, LangAvailability>) =
  LanguageAvailabilityState(
    hasLanguages = true,
    availableLanguages = languages.map { (language, availability) -> LanguageAvailabilityEntry(language, availability) },
  )

@Preview(
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PopupMode() {
  WideDialogTheme {
    MainScreen(
      onSettings = { },
      onLiveCamera = { },
      input = "Example input",
      output = TranslatedText("Example output", null),
      from = previewLanguage("az", "Azerbaijani"),
      to = previewLanguage("es", "Spanish"),
      detectedLanguage = previewLanguage("fr", "French"),
      displayImage = null,
      originalImage = null,
      imageWordSelection = null,
      detectedRegions = null,
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isImageProcessing = false,
      launchMode = LaunchMode.ReadWriteModal {},
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      downloads = emptyMap(),
      onDownload = {},
      onCancelDownload = {},
      settings = AppSettings(),
      dictionary = DictionaryState.Closed,
      inputTransliteration = null,
    )
  }
}

@Preview(
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun MainScreenPreview() {
  TranslatorTheme {
    MainScreen(
      onSettings = { },
      onLiveCamera = { },
      input = "Example input",
      output = TranslatedText("Example output", null),
      from = previewLanguage("en", "English"),
      to = previewLanguage("es", "Spanish"),
      detectedLanguage = previewLanguage("fr", "French"),
      displayImage = null,
      originalImage = null,
      imageWordSelection = null,
      detectedRegions = null,
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isImageProcessing = false,
      launchMode = LaunchMode.Normal,
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      downloads = emptyMap(),
      onDownload = {},
      onCancelDownload = {},
      settings = AppSettings(),
      dictionary = DictionaryState.Closed,
      inputTransliteration = null,
    )
  }
}

@Preview(
  showBackground = true,
  widthDp = 240,
  heightDp = 296,
)
@Composable
fun MainScreenCompactPreview() {
  TranslatorTheme {
    MainScreen(
      onSettings = { },
      onLiveCamera = { },
      input = "bonjour le monde comment ça va",
      output = TranslatedText("hello world how are you", null),
      from = previewLanguage("fr", "French"),
      to = previewLanguage("en", "English"),
      detectedLanguage = null,
      displayImage = null,
      originalImage = null,
      imageWordSelection = null,
      detectedRegions = null,
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isImageProcessing = false,
      launchMode = LaunchMode.Normal,
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      languageMetadata = emptyMap(),
      downloads = emptyMap(),
      onDownload = {},
      onCancelDownload = {},
      settings = AppSettings(),
      dictionary = DictionaryState.Closed,
      inputTransliteration = null,
    )
  }
}

@Preview(
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PreviewTranslitText() {
  TranslatorTheme {
    MainScreen(
      onSettings = { },
      onLiveCamera = { },
      input = "東京",
      output =
        TranslatedText(
          "Tokyo",
          null,
        ),
      from = previewLanguage("ja", "Japanese"),
      to = previewLanguage("en", "English"),
      detectedLanguage = null,
      displayImage = null,
      originalImage = null,
      imageWordSelection = null,
      detectedRegions = null,
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isImageProcessing = false,
      launchMode = LaunchMode.Normal,
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      downloads = emptyMap(),
      onDownload = {},
      onCancelDownload = {},
      settings = AppSettings(showTransliterationOnInput = true),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      dictionary = DictionaryState.Closed,
      inputTransliteration = "tōkyō",
    )
  }
}

@Preview(
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PreviewVeryLongText() {
  val vlong = "very long text. ".repeat(100)
  TranslatorTheme {
    MainScreen(
      onSettings = { },
      onLiveCamera = { },
      input = vlong,
      output =
        TranslatedText(
          vlong,
          null,
        ),
      from = previewLanguage("en", "English"),
      to = previewLanguage("en", "English"),
      detectedLanguage = null,
      displayImage = null,
      originalImage = null,
      imageWordSelection = null,
      detectedRegions = null,
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isImageProcessing = false,
      launchMode = LaunchMode.Normal,
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      downloads = emptyMap(),
      onDownload = {},
      onCancelDownload = {},
      settings = AppSettings(showTransliterationOnInput = true),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      dictionary = DictionaryState.Closed,
      inputTransliteration = "translit",
    )
  }
}

@Preview(
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PreviewVeryLongTextImage() {
  val vlong = "very long text. ".repeat(100)
  val context = LocalContext.current
  val drawable = ContextCompat.getDrawable(context, R.drawable.example)
  val bitmap = drawable?.toBitmap()

  TranslatorTheme {
    MainScreen(
      onSettings = { },
      onLiveCamera = { },
      input = vlong,
      output =
        TranslatedText(
          vlong,
          null,
        ),
      from = previewLanguage("en", "English"),
      to = previewLanguage("en", "English"),
      detectedLanguage = null,
      displayImage = bitmap,
      originalImage = bitmap,
      imageWordSelection = null,
      detectedRegions = null,
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isImageProcessing = false,
      launchMode = LaunchMode.Normal,
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      downloads = emptyMap(),
      onDownload = {},
      onCancelDownload = {},
      settings = AppSettings(),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      dictionary = DictionaryState.Closed,
      inputTransliteration = null,
    )
  }
}

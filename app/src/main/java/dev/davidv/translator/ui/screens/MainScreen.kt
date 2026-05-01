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

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import dev.davidv.translator.AppSettings
import dev.davidv.translator.DownloadService
import dev.davidv.translator.DownloadState
import dev.davidv.translator.LangAvailability
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityEntry
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.LaunchMode
import dev.davidv.translator.R
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.TranslatedText
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.TtsVoiceOption
import dev.davidv.translator.WordWithTaggedEntries
import dev.davidv.translator.ui.components.DetectedLanguageSection
import dev.davidv.translator.ui.components.DictionaryBottomSheet
import dev.davidv.translator.ui.components.ImageCaptureHandler
import dev.davidv.translator.ui.components.ImageDisplaySection
import dev.davidv.translator.ui.components.LanguageEvent
import dev.davidv.translator.ui.components.LanguageSelectionRow
import dev.davidv.translator.ui.components.SpeechPlaybackButton
import dev.davidv.translator.ui.components.StyledTextField
import dev.davidv.translator.ui.components.StyledTextFieldFocusController
import dev.davidv.translator.ui.components.TranslationField
import dev.davidv.translator.ui.components.ZoomableImageViewer
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Composable
fun MainScreen(
  // Navigation
  onSettings: () -> Unit,
  // Current state (read-only)
  input: String,
  inputTransliteration: String?,
  output: TranslatedText?,
  from: Language,
  to: Language,
  detectedLanguage: Language?,
  displayImage: Bitmap?,
  ocrReadingOrder: ReadingOrder,
  isTranslating: StateFlow<Boolean>,
  isOcrInProgress: StateFlow<Boolean>,
  dictionaryWord: WordWithTaggedEntries?,
  dictionaryStack: List<WordWithTaggedEntries>,
  dictionaryLookupLanguage: Language?,
  isAudioPlaying: Boolean = false,
  isAudioLoading: Boolean = false,
  isInputAudioPlaying: Boolean = false,
  isInputAudioLoading: Boolean = false,
  isOutputAudioPlaying: Boolean = false,
  isOutputAudioLoading: Boolean = false,
  // Action requests
  onMessage: (TranslatorMessage) -> Unit,
  canSwapLanguages: Boolean = true,
  onStopAudio: () -> Unit = {},
  // System integration
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  downloadStates: Map<Language, DownloadState> = emptyMap(),
  settings: AppSettings,
  availableSourceTtsVoices: List<TtsVoiceOption> = emptyList(),
  selectedSourceTtsVoiceName: String? = null,
  sourceTtsPlaybackSpeed: Float = 1.0f,
  availableTtsVoices: List<TtsVoiceOption> = emptyList(),
  selectedTtsVoiceName: String? = null,
  targetTtsPlaybackSpeed: Float = 1.0f,
  onTtsPlaybackSpeedChange: (Float) -> Unit = {},
  onSourceTtsPlaybackSpeedChange: (Float) -> Unit = {},
  onSourceTtsVoiceSelected: (String) -> Unit = {},
  onTtsVoiceSelected: (String) -> Unit = {},
  onSpeakInput: (String, Language) -> Unit = { _, _ -> },
  onSpeakOutput: (String, Language) -> Unit = { _, _ -> },
  launchMode: LaunchMode,
  pendingSharedImage: SharedFlow<android.net.Uri>? = null,
  isAutoSource: Boolean = false,
) {
  var showFullScreenImage by remember { mutableStateOf(false) }
  var showImageSourceSheet by remember { mutableStateOf(false) }
  val inputFocusController = remember { StyledTextFieldFocusController() }
  val extraTopPadding = if (launchMode == LaunchMode.Normal) 0.dp else 8.dp
  val context = LocalContext.current
  val showOnlyOutputInReadonlyModal =
    launchMode == LaunchMode.ReadonlyModal && settings.onlyShowOutputOnReadonlyModal
  val detectedInstalled =
    detectedLanguage?.takeIf { languageState.availabilityFor(it)?.translatorFiles == true }

  // Handle back button when dictionary is open
  BackHandler(enabled = dictionaryWord != null) {
    onMessage(TranslatorMessage.ClearDictionaryStack)
  }

  Scaffold(
    modifier = Modifier.semantics { contentDescription = "Main screen" },
    floatingActionButton = {
      when (launchMode) {
        LaunchMode.Normal -> {
          if (!settings.disableOcr) {
            val fabDisabled = isAutoSource
            FloatingActionButton(
              onClick = {
                if (fabDisabled) {
                  android.widget.Toast
                    .makeText(context, "Please select source language first", android.widget.Toast.LENGTH_SHORT)
                    .show()
                } else {
                  showImageSourceSheet = true
                }
              },
              containerColor =
                if (fabDisabled) {
                  MaterialTheme.colorScheme.surfaceVariant
                } else {
                  FloatingActionButtonDefaults.containerColor
                },
              contentColor =
                if (fabDisabled) {
                  MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                } else {
                  MaterialTheme.colorScheme.onPrimaryContainer
                },
            ) {
              Icon(
                painterResource(
                  id =
                    if (settings.showFilePickerInImagePicker) {
                      R.drawable.attach_file_add
                    } else {
                      R.drawable.add_photo
                    },
                ),
                contentDescription =
                  if (settings.showFilePickerInImagePicker) {
                    "Translate image or file"
                  } else {
                    "Translate image"
                  },
              )
            }
          }
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
                contentDescription = "Replace text",
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
          .imePadding()
          .padding(top = paddingValues.calculateTopPadding() + extraTopPadding, bottom = 8.dp),
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
      ) {
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

        BoxWithConstraints(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f),
        ) {
          val parentHeight = maxHeight

          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .let { modifier ->
                  if (displayImage != null) {
                    modifier.verticalScroll(rememberScrollState())
                  } else {
                    modifier
                  }
                },
          ) {
            if (displayImage != null) {
              Box(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .heightIn(max = parentHeight * 0.7f),
              ) {
                ImageDisplaySection(
                  displayImage = displayImage,
                  isOcrInProgress = isOcrInProgress,
                  isTranslating = isTranslating,
                  onShowFullScreenImage = { showFullScreenImage = true },
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
                  ShareImage(onMessage)
                  ClearInput(
                    onMessage = onMessage,
                    showBackdrop = true,
                  )
                }
              }
            }

            if (!showOnlyOutputInReadonlyModal && (displayImage == null || settings.showOCRDetection)) {
              val showTranslit = settings.showTransliterationOnInput && inputTransliteration != null
              Column(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .let { m ->
                      if (displayImage == null) m.weight(1f, fill = true) else m.height(parentHeight * 0.5f)
                    },
              ) {
                Box(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .weight(3f, fill = true)
                      .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                      ) { inputFocusController.focus() },
                ) {
                  StyledTextField(
                    text = input,
                    onValueChange = { newInput ->
                      onMessage(TranslatorMessage.TextInput(newInput))
                    },
                    onDictionaryLookup = { word ->
                      onMessage(TranslatorMessage.DictionaryLookup(word, from))
                    },
                    placeholder = if (displayImage == null) "Enter text" else null,
                    modifier =
                      Modifier
                        .fillMaxWidth()
                        .padding(end = if (displayImage == null) 24.dp else 0.dp),
                    textStyle =
                      MaterialTheme.typography.bodyLarge.copy(
                        fontSize = (MaterialTheme.typography.bodyLarge.fontSize * settings.fontFactor),
                        lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight * settings.fontFactor),
                      ),
                    focusController = inputFocusController,
                  )
                  if (displayImage == null) {
                    Column(
                      modifier = Modifier.align(Alignment.TopEnd),
                      horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                      Row {
                        if (input.isNotEmpty()) {
                          ClearInput(onMessage)
                        } else {
                          PasteButton(onMessage)
                        }
                      }
                      val isOtherAudioActive = (isAudioPlaying || isAudioLoading) && !isInputAudioPlaying && !isInputAudioLoading
                      if (input.isNotBlank() && languageState.availabilityFor(from)?.ttsFiles == true && !isOtherAudioActive) {
                        SpeechPlaybackButton(
                          isAudioPlaying = isInputAudioPlaying,
                          isAudioLoading = isInputAudioLoading,
                          speechPlaybackSpeed = sourceTtsPlaybackSpeed,
                          selectedVoiceName = selectedSourceTtsVoiceName,
                          availableVoices = availableSourceTtsVoices,
                          onSpeak = {
                            if (isInputAudioPlaying || isInputAudioLoading) {
                              onStopAudio()
                            } else {
                              onSpeakInput(input, from)
                            }
                          },
                          onSpeechPlaybackSpeedChange = onSourceTtsPlaybackSpeedChange,
                          onVoiceSelected = onSourceTtsVoiceSelected,
                          contentDescription = if (isInputAudioPlaying) "Stop audio" else "Speak input",
                          modifier = Modifier.padding(top = 6.dp),
                        )
                      }
                    }
                  }
                }
                if (showTranslit) {
                  val textStyle = MaterialTheme.typography.bodyLarge
                  val fontSize = textStyle.fontSize.value
                  val smallerFontSize = fontSize * 0.7f
                  val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                  Box(
                    modifier =
                      Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .clickable { inputFocusController.focus() },
                  ) {
                    AndroidView(
                      factory = { context ->
                        TextView(context).apply {
                          this.text = inputTransliteration
                          this.textSize = smallerFontSize
                          this.setTextColor(textColor)
                          this.movementMethod =
                            android.text.method.ScrollingMovementMethod
                              .getInstance()
                          this.isClickable = false
                          this.isLongClickable = false
                          this.isFocusable = false
                        }
                      },
                      update = { textView ->
                        textView.text = inputTransliteration
                        textView.textSize = smallerFontSize
                      },
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }
                }
              }
            }

            DetectedLanguageSection(
              detectedLanguage = detectedLanguage,
              from = from,
              languageState = languageState,
              onMessage = onMessage,
              downloadStates = downloadStates,
              isAutoSource = isAutoSource,
              onEvent = { event ->
                when (event) {
                  is LanguageEvent.Download -> DownloadService.startDownload(context, event.language)
                  is LanguageEvent.Cancel -> DownloadService.cancelDownload(context, event.language)
                  else -> Log.e("MainScreen", "Got unexpected event: $event")
                }
              },
            )

            if (!showOnlyOutputInReadonlyModal) {
              Box(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
              ) {
                HorizontalDivider(
                  modifier = Modifier.fillMaxWidth(0.5f),
                  thickness = 1.dp,
                  color = MaterialTheme.colorScheme.outlineVariant,
                )
              }
            }

            Box(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .let { m ->
                    if (displayImage == null) m.weight(1f, fill = true) else m.height(parentHeight * 0.5f)
                  },
            ) {
              val isOtherAudioActive = (isAudioPlaying || isAudioLoading) && !isOutputAudioPlaying && !isOutputAudioLoading
              TranslationField(
                text = output,
                textStyle =
                  MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (MaterialTheme.typography.bodyLarge.fontSize * settings.fontFactor),
                    lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight * settings.fontFactor),
                  ),
                onDictionaryLookup = {
                  onMessage(TranslatorMessage.DictionaryLookup(it, to))
                },
                canSpeak = languageState.availabilityFor(to)?.ttsFiles == true && !isOtherAudioActive,
                isAudioPlaying = isOutputAudioPlaying,
                isAudioLoading = isOutputAudioLoading,
                speechPlaybackSpeed = targetTtsPlaybackSpeed,
                selectedVoiceName = selectedTtsVoiceName,
                availableVoices = availableTtsVoices,
                onSpeak = {
                  if (isOutputAudioPlaying || isOutputAudioLoading) {
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

  // Image capture handling
  ImageCaptureHandler(
    onMessage = onMessage,
    showImageSourceSheet = showImageSourceSheet,
    onDismissImageSourceSheet = { showImageSourceSheet = false },
    showFilePickerInImagePicker = settings.showFilePickerInImagePicker,
    pendingSharedImage = pendingSharedImage,
  )

  // Full screen image viewer
  if (showFullScreenImage && displayImage != null) {
    ZoomableImageViewer(
      bitmap = displayImage,
      onDismiss = { showFullScreenImage = false },
      onShare = {
        onMessage(TranslatorMessage.ShareTranslatedImage)
      },
    )
  }

  if (dictionaryWord != null && dictionaryLookupLanguage != null) {
    DictionaryBottomSheet(
      dictionaryWord = dictionaryWord,
      dictionaryStack = dictionaryStack,
      dictionaryLookupLanguage = dictionaryLookupLanguage,
      onDismiss = {
        onMessage(TranslatorMessage.ClearDictionaryStack)
      },
      onDictionaryLookup = { word ->
        onMessage(TranslatorMessage.DictionaryLookup(word, dictionaryLookupLanguage))
      },
      onBackPressed = {
        onMessage(TranslatorMessage.PopDictionary)
      },
    )
  }
}

@Composable
fun ShareImage(onMessage: (TranslatorMessage) -> Unit) {
  ActionPillButton(
    iconRes = R.drawable.share,
    contentDescription = "Share image",
    showBackdrop = true,
    onClick = { onMessage(TranslatorMessage.ShareTranslatedImage) },
  )
}

@Composable
fun JapaneseOcrModeToggle(
  readingOrder: ReadingOrder,
  onMessage: (TranslatorMessage) -> Unit,
) {
  val isVertical = readingOrder == ReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT
  ActionPillButton(
    iconRes = if (isVertical) R.drawable.text_rotate_vertical else R.drawable.text_rotation_none,
    contentDescription = if (isVertical) "Japanese OCR vertical mode" else "Japanese OCR horizontal mode",
    showBackdrop = true,
    onClick = { onMessage(TranslatorMessage.ToggleJapaneseOcrMode) },
  )
}

@Composable
fun ClearInput(
  onMessage: (TranslatorMessage) -> Unit,
  showBackdrop: Boolean = false,
) {
  ActionPillButton(
    iconRes = R.drawable.cancel,
    contentDescription = "Clear input",
    showBackdrop = showBackdrop,
    onClick = { onMessage(TranslatorMessage.ClearInput) },
  )
}

@Composable
fun PasteButton(
  onMessage: (TranslatorMessage) -> Unit,
  showBackdrop: Boolean = false,
) {
  val context = LocalContext.current

  ActionPillButton(
    iconRes = R.drawable.paste,
    contentDescription = "Paste",
    showBackdrop = showBackdrop,
    onClick = {
      val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val clipData = clipboardManager.primaryClip
      if (clipData != null && clipData.itemCount > 0) {
        val text = clipData.getItemAt(0).text?.toString() ?: ""
        onMessage(TranslatorMessage.TextInput(text))
      }
    },
  )
}

@Composable
private fun ActionPillButton(
  iconRes: Int,
  contentDescription: String,
  showBackdrop: Boolean = false,
  onClick: () -> Unit,
) {
  if (showBackdrop) {
    Surface(
      shape = CircleShape,
      color = Color(0xCC303030),
    ) {
      IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
      ) {
        Icon(
          painterResource(id = iconRes),
          contentDescription = contentDescription,
          tint = Color.White,
        )
      }
    }
  } else {
    IconButton(
      onClick = onClick,
      modifier = Modifier.size(36.dp),
    ) {
      Icon(
        painterResource(id = iconRes),
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
    }
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
  tessName = code,
  script = "Latn",
  dictionaryCode = code,
  tessdataSizeBytes = 0,
)

private fun previewLanguageState(vararg languages: Pair<Language, LangAvailability>) =
  LanguageAvailabilityState(
    hasLanguages = true,
    availableLanguages = languages.map { (language, availability) -> LanguageAvailabilityEntry(language, availability) },
    isChecking = false,
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
      input = "Example input",
      output = TranslatedText("Example output", null),
      from = previewLanguage("az", "Azerbaijani"),
      to = previewLanguage("es", "Spanish"),
      detectedLanguage = previewLanguage("fr", "French"),
      displayImage = null,
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isTranslating = MutableStateFlow(false).asStateFlow(),
      isOcrInProgress = MutableStateFlow(false).asStateFlow(),
      launchMode = LaunchMode.ReadWriteModal {},
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      downloadStates = emptyMap(),
      settings = AppSettings(),
      dictionaryWord = null,
      dictionaryStack = emptyList(),
      dictionaryLookupLanguage = null,
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
      input = "Example input",
      output = TranslatedText("Example output", null),
      from = previewLanguage("en", "English"),
      to = previewLanguage("es", "Spanish"),
      detectedLanguage = previewLanguage("fr", "French"),
      displayImage = null,
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isTranslating = MutableStateFlow(false).asStateFlow(),
      isOcrInProgress = MutableStateFlow(false).asStateFlow(),
      launchMode = LaunchMode.Normal,
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      downloadStates = emptyMap(),
      settings = AppSettings(),
      dictionaryWord = null,
      dictionaryStack = emptyList(),
      dictionaryLookupLanguage = null,
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
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isTranslating = MutableStateFlow(false).asStateFlow(),
      isOcrInProgress = MutableStateFlow(false).asStateFlow(),
      launchMode = LaunchMode.Normal,
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      downloadStates = emptyMap(),
      settings = AppSettings(showTransliterationOnInput = true),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      dictionaryWord = null,
      dictionaryStack = emptyList(),
      dictionaryLookupLanguage = null,
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
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isTranslating = MutableStateFlow(false).asStateFlow(),
      isOcrInProgress = MutableStateFlow(false).asStateFlow(),
      launchMode = LaunchMode.Normal,
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      downloadStates = emptyMap(),
      settings = AppSettings(showTransliterationOnInput = true),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      dictionaryWord = null,
      dictionaryStack = emptyList(),
      dictionaryLookupLanguage = null,
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
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
      isTranslating = MutableStateFlow(false).asStateFlow(),
      isOcrInProgress = MutableStateFlow(false).asStateFlow(),
      launchMode = LaunchMode.Normal,
      onMessage = {},
      languageState =
        previewLanguageState(
          previewLanguage("en", "English") to LangAvailability(true, true, true, true),
          previewLanguage("es", "Spanish") to LangAvailability(true, true, true, true),
          previewLanguage("fr", "French") to LangAvailability(true, true, true, true),
        ),
      downloadStates = emptyMap(),
      settings = AppSettings(),
      languageMetadata = mapOf(previewLanguage("es", "Spanish") to LanguageMetadata(favorite = true)),
      dictionaryWord = null,
      dictionaryStack = emptyList(),
      dictionaryLookupLanguage = null,
      inputTransliteration = null,
    )
  }
}

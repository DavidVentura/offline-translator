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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.davidv.translator.Language
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.localizedName
import dev.davidv.translator.ui.theme.AppLayout
import dev.davidv.translator.ui.theme.LayoutDensity

private enum class InputSourcePlacement {
  Hidden,
  Footer,
  HeaderMenu,
}

@Composable
fun InputSection(
  input: String,
  inputTransliteration: String?,
  from: Language,
  onMessage: (TranslatorMessage) -> Unit,
  focusController: StyledTextFieldFocusController,
  fontFactor: Float,
  showTransliteration: Boolean,
  canInputDict: Boolean,
  inputDictMode: Boolean,
  onToggleInputDict: () -> Unit,
  canSpeakInput: Boolean,
  isInputAudioPlaying: Boolean,
  isInputAudioLoading: Boolean,
  sourceTtsPlaybackSpeed: Float,
  selectedSourceTtsVoiceName: String?,
  availableSourceTtsVoices: List<uniffi.translator_core.InstalledTtsPack>,
  onSourceTtsPlaybackSpeedChange: (Float) -> Unit,
  onSourceTtsVoiceSelected: (String, String) -> Unit,
  onSpeakInput: (String, Language) -> Unit,
  onStopAudio: () -> Unit,
  showSourceRow: Boolean,
  sourceActions: ImageSourceActions,
  onCameraClick: () -> Unit,
  screenTranslateEnabled: Boolean,
  isAutoSource: Boolean,
  detectedSection: (@Composable () -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val inputTextStyle =
    MaterialTheme.typography.bodyLarge.copy(
      fontSize = (MaterialTheme.typography.bodyLarge.fontSize * fontFactor),
      lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight * fontFactor),
    )

  val sourcePlacement =
    if (!showSourceRow) {
      InputSourcePlacement.Hidden
    } else {
      when (AppLayout.density) {
        LayoutDensity.Regular -> InputSourcePlacement.Footer
        LayoutDensity.Compact -> InputSourcePlacement.HeaderMenu
      }
    }

  TranslationCard(
    label = from.localizedName(),
    modifier = modifier,
    tools = {
      if (canSpeakInput) {
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
          contentDescription =
            if (isInputAudioPlaying) {
              stringResource(R.string.a11y_stop_audio)
            } else {
              stringResource(R.string.a11y_speak_input)
            },
        )
      }
      if (canInputDict) {
        ToolIconButton(
          iconRes = R.drawable.dictionary_book,
          contentDescription =
            stringResource(
              if (inputDictMode) {
                R.string.a11y_dictionary_mode_on
              } else {
                R.string.a11y_dictionary_mode_off
              },
            ),
          active = inputDictMode,
          onClick = onToggleInputDict,
        )
      }
      if (input.isNotEmpty()) {
        ToolIconButton(
          iconRes = R.drawable.cancel,
          contentDescription = stringResource(R.string.a11y_clear_input),
          onClick = { onMessage(TranslatorMessage.ClearInput) },
        )
      } else if (rememberClipboardHasText()) {
        ToolIconButton(
          iconRes = R.drawable.paste,
          contentDescription = stringResource(R.string.a11y_paste),
          onClick = { pasteFromClipboard(context, onMessage) },
        )
      }
      if (sourcePlacement == InputSourcePlacement.HeaderMenu) {
        InputSourceMenu(
          input = input,
          from = from,
          onMessage = onMessage,
          actions = sourceActions,
          onCameraClick = onCameraClick,
          screenTranslateEnabled = screenTranslateEnabled,
          isAutoSource = isAutoSource,
        )
      }
    },
    footer =
      if (sourcePlacement == InputSourcePlacement.Footer) {
        {
          InputSourceRow(
            input = input,
            from = from,
            onMessage = onMessage,
            actions = sourceActions,
            onCameraClick = onCameraClick,
            screenTranslateEnabled = screenTranslateEnabled,
            isAutoSource = isAutoSource,
          )
        }
      } else {
        null
      },
    body = {
      Column(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f, fill = true)
              .verticalScroll(rememberScrollState())
              .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !inputDictMode,
              ) { focusController.focus() },
        ) {
          StyledTextField(
            text = input,
            onValueChange = { newInput ->
              onMessage(TranslatorMessage.TextInput(newInput))
            },
            onDictionaryLookup = { word ->
              onMessage(TranslatorMessage.DictionaryLookup(word, from))
            },
            wordTapMode = inputDictMode,
            onWordTap = { offset ->
              val word = wordAt(input, offset)
              if (!word.isNullOrBlank()) {
                onMessage(TranslatorMessage.DictionaryLookup(word, from))
              }
            },
            placeholder = stringResource(R.string.main_input_placeholder),
            modifier = Modifier.fillMaxWidth(),
            textStyle = inputTextStyle,
            focusController = focusController,
          )

          if (showTransliteration && inputTransliteration != null) {
            TransliterationText(
              text = inputTransliteration,
              mainStyle = inputTextStyle,
              modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
          }
        }

        detectedSection?.invoke()
      }
    },
  )
}

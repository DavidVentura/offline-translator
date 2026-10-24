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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.davidv.translator.R
import dev.davidv.translator.TranslatedText
import dev.davidv.translator.ui.theme.AppLayout
import dev.davidv.translator.ui.theme.TranslatorTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranslationField(
  text: TranslatedText?,
  modifier: Modifier = Modifier,
  label: String = "",
  header: (@Composable () -> Unit)? = null,
  textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
  onDictionaryLookup: (String) -> Unit = {},
  onAlternatives: ((Int, Int) -> Unit)? = null,
  hasAlternatives: ((Int, Int) -> Boolean)? = null,
  underlineRanges: List<IntRange> = emptyList(),
  highlightRange: IntRange? = null,
  tapMode: OutputTapMode = OutputTapMode.None,
  onToggleAlternativesMode: (() -> Unit)? = null,
  onToggleDictionaryMode: (() -> Unit)? = null,
  dictionaryAvailable: Boolean = false,
  onDictionaryUnavailable: () -> Unit = {},
  speakerAvailable: Boolean = false,
  onSpeakerUnavailable: () -> Unit = {},
  onWordTap: ((Int) -> Unit)? = null,
  isAudioPlaying: Boolean = false,
  isAudioLoading: Boolean = false,
  speechPlaybackSpeed: Float = 1.0f,
  selectedVoiceName: String? = null,
  availableVoices: List<uniffi.translator_core.InstalledTtsPack> = emptyList(),
  onSpeak: () -> Unit = {},
  onSpeechPlaybackSpeedChange: (Float) -> Unit = {},
  onVoiceSelected: (String, String) -> Unit = { _, _ -> },
) {
  val context = LocalContext.current

  val actionModeCallback =
    remember(onDictionaryLookup, onAlternatives, hasAlternatives) {
      DictionaryActionModeCallback(context, onDictionaryLookup, onAlternatives, hasAlternatives)
    }

  val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
  val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f).toArgb()
  val fontSize = textStyle.fontSize.value
  val translationOutputDescription = stringResource(R.string.a11y_translation_output)

  TranslationCard(
    label = label,
    labelContent = header,
    modifier =
      modifier
        .semantics {
          contentDescription = translationOutputDescription
          this.text = AnnotatedString(text?.translated ?: "")
        },
    tools = {
      if (text?.translated?.isNotEmpty() == true) {
        if (speakerAvailable) {
          SpeechPlaybackButton(
            isAudioPlaying = isAudioPlaying,
            isAudioLoading = isAudioLoading,
            speechPlaybackSpeed = speechPlaybackSpeed,
            selectedVoiceName = selectedVoiceName,
            availableVoices = availableVoices,
            onSpeak = onSpeak,
            onSpeechPlaybackSpeedChange = onSpeechPlaybackSpeedChange,
            onVoiceSelected = onVoiceSelected,
            contentDescription =
              if (isAudioPlaying) {
                stringResource(R.string.a11y_stop_audio)
              } else {
                stringResource(R.string.a11y_speak_translation)
              },
          )
        } else {
          ToolIconButton(
            iconRes = R.drawable.volume_up,
            contentDescription = stringResource(R.string.a11y_speak_translation),
            available = false,
            onClick = onSpeakerUnavailable,
          )
        }

        ToolIconButton(
          iconRes = R.drawable.alt_route,
          contentDescription =
            stringResource(
              if (tapMode == OutputTapMode.Alternatives) {
                R.string.a11y_alternatives_mode_on
              } else {
                R.string.a11y_alternatives_mode_off
              },
            ),
          active = tapMode == OutputTapMode.Alternatives,
          onClick = onToggleAlternativesMode ?: {},
        )

        if (dictionaryAvailable) {
          ToolIconButton(
            iconRes = R.drawable.dictionary_book,
            contentDescription =
              stringResource(
                if (tapMode == OutputTapMode.Dictionary) {
                  R.string.a11y_dictionary_mode_on
                } else {
                  R.string.a11y_dictionary_mode_off
                },
              ),
            active = tapMode == OutputTapMode.Dictionary,
            onClick = onToggleDictionaryMode ?: {},
          )
        } else {
          ToolIconButton(
            iconRes = R.drawable.dictionary_book,
            contentDescription = stringResource(R.string.a11y_dictionary_mode_off),
            available = false,
            onClick = onDictionaryUnavailable,
          )
        }

        ToolIconButton(
          iconRes = R.drawable.copy,
          contentDescription = stringResource(R.string.a11y_copy_translation),
          onClick = {
            val clipboard =
              context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Translation", text.translated)
            clipboard.setPrimaryClip(clip)
          },
        )
      }
    },
    body = {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
      ) {
        AndroidView(
          modifier = Modifier.fillMaxWidth(),
          factory = { context ->
            DottedUnderlineTextView(context).apply {
              this.tag = "output_textview_tag"
              this.contentDescription = context.getString(R.string.a11y_output_textview)
              this.text = text?.translated ?: ""
              this.textSize = fontSize
              this.setTextColor(textColor)
              this.setTextIsSelectable(true)
              this.customSelectionActionModeCallback = actionModeCallback
              this.customInsertionActionModeCallback = actionModeCallback
              this.underlineRanges = underlineRanges
              this.wordHighlightColor = highlightColor
              this.highlightRange = highlightRange
              this.wordTapListener = onWordTap
              this.wordTapMode = tapMode != OutputTapMode.None
              actionModeCallback.setTextView(this)
            }
          },
          update = { textView ->
            textView.text = text?.translated ?: ""
            textView.textSize = fontSize
            textView.wordTapListener = onWordTap
            // Toggle selection off/on before re-applying the selection callback,
            // since leaving the mode re-enables selection.
            textView.wordTapMode = tapMode != OutputTapMode.None
            textView.customSelectionActionModeCallback = actionModeCallback
            textView.underlineRanges = underlineRanges
            textView.wordHighlightColor = highlightColor
            textView.highlightRange = highlightRange
            actionModeCallback.setTextView(textView)
          },
        )

        if (text?.transliterated != null) {
          TransliterationText(
            text = text.transliterated,
            mainStyle = textStyle,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
          )
        }
      }
    },
  )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeechPlaybackButton(
  isAudioPlaying: Boolean,
  isAudioLoading: Boolean,
  speechPlaybackSpeed: Float,
  selectedVoiceName: String?,
  availableVoices: List<uniffi.translator_core.InstalledTtsPack>,
  onSpeak: () -> Unit,
  onSpeechPlaybackSpeedChange: (Float) -> Unit,
  onVoiceSelected: (String, String) -> Unit,
  contentDescription: String,
  modifier: Modifier = Modifier,
) {
  var showSpeechOptions by remember { mutableStateOf(false) }
  val active = isAudioPlaying || isAudioLoading
  val tint =
    if (active) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.onSurfaceVariant
    }

  val dimens = AppLayout.dimens
  Box(
    modifier =
      modifier
        .size(dimens.toolButtonSize),
  ) {
    Box(
      modifier =
        Modifier
          .matchParentSize()
          .clip(RoundedCornerShape(12.dp))
          .background(
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
          ).combinedClickable(
            onClick = onSpeak,
            onLongClick = {
              showSpeechOptions = true
            },
          ).semantics {
            this.contentDescription = contentDescription
          },
      contentAlignment = Alignment.Center,
    ) {
      if (isAudioLoading && !isAudioPlaying) {
        CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.dp,
          color = tint,
        )
      } else {
        Icon(
          painter = painterResource(id = if (isAudioPlaying) R.drawable.stop else R.drawable.volume_up),
          contentDescription = null,
          tint = tint,
          modifier = Modifier.size(dimens.toolIconSize),
        )
      }
    }

    DropdownMenu(
      expanded = showSpeechOptions,
      onDismissRequest = { showSpeechOptions = false },
    ) {
      Column(
        modifier =
          Modifier
            .widthIn(min = 220.dp, max = 280.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Text(
          text = stringResource(R.string.voice_playback_speed),
          style = MaterialTheme.typography.labelLarge,
        )
        SpeechSpeedControl(
          speed = speechPlaybackSpeed,
          onSpeedChange = onSpeechPlaybackSpeedChange,
          modifier =
            Modifier
              .padding(top = 8.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text(
          text = stringResource(R.string.voice_label),
          style = MaterialTheme.typography.labelLarge,
        )
        Column(
          modifier =
            Modifier
              .padding(top = 8.dp)
              .heightIn(max = 220.dp)
              .verticalScroll(rememberScrollState()),
        ) {
          if (availableVoices.isEmpty()) {
            Text(
              text = stringResource(R.string.voice_default),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            availableVoices.forEach { pack ->
              val isMulti = pack.voices.size > 1
              if (isMulti) {
                Text(
                  text = pack.displayName.uppercase(),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                pack.voices.forEach { speaker ->
                  VoiceRowEntry(
                    label = speaker.displayName,
                    isSelected = speaker.name == selectedVoiceName,
                    indented = true,
                    onClick = {
                      onVoiceSelected(pack.packId, speaker.name)
                      showSpeechOptions = false
                    },
                  )
                }
              } else {
                val speaker = pack.voices.first()
                VoiceRowEntry(
                  label = pack.displayName,
                  isSelected = speaker.name == selectedVoiceName,
                  indented = false,
                  onClick = {
                    onVoiceSelected(pack.packId, speaker.name)
                    showSpeechOptions = false
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceRowEntry(
  label: String,
  isSelected: Boolean,
  indented: Boolean,
  onClick: () -> Unit,
) {
  Text(
    text = label,
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(
          if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
          } else {
            MaterialTheme.colorScheme.surface
          },
        ).combinedClickable(
          onClick = onClick,
          onLongClick = {},
        ).padding(
          start = if (indented) 20.dp else 10.dp,
          end = 10.dp,
          top = 8.dp,
          bottom = 8.dp,
        ),
    color =
      if (isSelected) {
        MaterialTheme.colorScheme.onSecondaryContainer
      } else {
        MaterialTheme.colorScheme.onSurface
      },
    style = MaterialTheme.typography.bodyMedium,
  )
  Spacer(modifier = Modifier.size(4.dp))
}

@Preview(
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun TranslationFieldBothWeightedPreview() {
  TranslatorTheme {
    Column(modifier = Modifier.fillMaxSize()) {
      TranslationField(
        text =
          TranslatedText(
            "very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text. very long text.",
            null,
          ),
      )
    }
  }
}

@Preview(
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun WithTransliteration() {
  TranslatorTheme {
    Column(modifier = Modifier.fillMaxSize()) {
      TranslationField(
        text = TranslatedText("some words", "transliterated"),
      )
    }
  }
}

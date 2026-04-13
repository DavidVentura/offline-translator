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
import android.widget.TextView
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import dev.davidv.translator.TtsVoiceOption
import dev.davidv.translator.ui.theme.TranslatorTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranslationField(
  text: TranslatedText?,
  textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
  onDictionaryLookup: (String) -> Unit = {},
  canSpeak: Boolean = false,
  isAudioPlaying: Boolean = false,
  isAudioLoading: Boolean = false,
  speechPlaybackSpeed: Float = 1.0f,
  selectedVoiceName: String? = null,
  availableVoices: List<TtsVoiceOption> = emptyList(),
  onSpeak: () -> Unit = {},
  onSpeechPlaybackSpeedChange: (Float) -> Unit = {},
  onVoiceSelected: (String) -> Unit = {},
) {
  val context = LocalContext.current
  var showSpeechOptions by remember { mutableStateOf(false) }

  val actionModeCallback =
    remember(onDictionaryLookup) {
      DictionaryActionModeCallback(context, onDictionaryLookup)
    }

  val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
  val fontSize = textStyle.fontSize.value
  val smallerFontSize = fontSize * 0.7f

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .semantics {
          contentDescription = "Translation output"
          this.text = AnnotatedString(text?.translated ?: "")
        },
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          // Leave space for trailing action buttons.
          .padding(end = 32.dp),
      contentAlignment = Alignment.TopStart,
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
      ) {
        AndroidView(
          modifier = Modifier.fillMaxWidth(),
          factory = { context ->
            TextView(context).apply {
              this.tag = "output_textview_tag"
              this.contentDescription = "Output textview"
              this.text = text?.translated ?: ""
              this.textSize = fontSize
              this.setTextColor(textColor)
              this.setTextIsSelectable(true)
              this.customSelectionActionModeCallback = actionModeCallback
              this.customInsertionActionModeCallback = actionModeCallback
              actionModeCallback.setTextView(this)
            }
          },
          update = { textView ->
            textView.text = text?.translated ?: ""
            textView.textSize = fontSize
            textView.customSelectionActionModeCallback = actionModeCallback
            actionModeCallback.setTextView(textView)
          },
        )

        if (text?.transliterated != null) {
          AndroidView(
            factory = { context ->
              TextView(context).apply {
                this.text = text.transliterated
                this.textSize = smallerFontSize
                this.setTextColor(textColor)
                this.setTextIsSelectable(true)
              }
            },
            update = { textView ->
              textView.text = text.transliterated
              textView.textSize = smallerFontSize
            },
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
          )
        }
      }
    }

    if (text?.translated?.isNotEmpty() == true) {
      Column(
        modifier =
          Modifier
            .align(Alignment.TopEnd),
      ) {
        IconButton(
          onClick = {
            val clipboard =
              context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Translation", text.translated)
            clipboard.setPrimaryClip(clip)
          },
          modifier = Modifier.size(24.dp),
        ) {
          Icon(
            painterResource(id = R.drawable.copy),
            contentDescription = "Copy translation",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
          )
        }

        if (canSpeak || isAudioLoading || isAudioPlaying) {
          Box(
            modifier =
              Modifier
                .padding(top = 6.dp)
                .size(24.dp),
          ) {
            Box(
              modifier =
                Modifier
                  .matchParentSize()
                  .clip(RoundedCornerShape(8.dp))
                  .combinedClickable(
                    onClick = onSpeak,
                    onLongClick = {
                      showSpeechOptions = true
                    },
                  ).semantics {
                    contentDescription = if (isAudioPlaying) "Stop audio" else "Speak translation"
                  },
              contentAlignment = Alignment.Center,
            ) {
              if (isAudioLoading && !isAudioPlaying) {
                CircularProgressIndicator(
                  modifier = Modifier.size(18.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
              } else {
                Icon(
                  painter = painterResource(id = if (isAudioPlaying) R.drawable.stop else R.drawable.volume_up),
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
                  text = "Playback speed",
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
                  text = "Voice",
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
                      text = "Default voice",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  } else {
                    availableVoices.forEach { voice ->
                      val isSelected = voice.name == selectedVoiceName
                      Text(
                        text = voice.displayName,
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
                              onClick = {
                                onVoiceSelected(voice.name)
                                showSpeechOptions = false
                              },
                              onLongClick = {},
                            ).padding(horizontal = 10.dp, vertical = 8.dp),
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
                  }
                }
              }
            }
          }
        }
      }
    }
  }
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

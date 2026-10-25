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

import android.net.Uri

enum class FromLangChange {
  KeepTarget,
  MovePreviousToTarget,
}

sealed class TranslatorMessage {
  // Sent only from screens that exist while a language pair is selected; the pair they act on
  // is the session's current one.
  sealed interface PairMessage

  data class TextInput(
    val text: String,
  ) : TranslatorMessage(),
    PairMessage

  // Text picked off an image, in the language that image layer was read or translated as.
  data class EditAsText(
    val text: String,
    val language: Language,
  ) : TranslatorMessage(),
    PairMessage

  data class FromLang(
    val language: Language,
    val change: FromLangChange = FromLangChange.KeepTarget,
  ) : TranslatorMessage(),
    PairMessage

  data class ToLang(
    val language: Language,
  ) : TranslatorMessage(),
    PairMessage

  data class AddTab(
    val language: Language,
  ) : TranslatorMessage(),
    PairMessage

  data class RemoveTab(
    val language: Language,
  ) : TranslatorMessage(),
    PairMessage

  data class SetImageUri(
    val uri: Uri,
    val deleteAfterLoad: Boolean = false,
  ) : TranslatorMessage(),
    PairMessage

  data class SetDocumentPath(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val deleteAfterLoad: Boolean = false,
  ) : TranslatorMessage()

  data object ShareTranslatedImage : TranslatorMessage()

  data object ToggleJapaneseOcrMode : TranslatorMessage(), PairMessage

  data object SwapLanguages : TranslatorMessage(), PairMessage

  data object EnableAutoSource : TranslatorMessage(), PairMessage

  data object ClearInput : TranslatorMessage()

  data class DictionaryLookup(
    val str: String,
    val language: Language,
  ) : TranslatorMessage()

  /**
   * Re-translate the current output forcing [forcedPrefix] as the start of the
   * translation — used when the user picks a word alternative. The source and
   * languages come from current state.
   */
  data class Steer(
    val forcedPrefix: String,
  ) : TranslatorMessage(),
    PairMessage

  data object PopDictionary : TranslatorMessage()

  data object ClearDictionaryStack : TranslatorMessage()

  data class ChangeLaunchMode(
    val newLaunchMode: LaunchMode,
  ) : TranslatorMessage()
}

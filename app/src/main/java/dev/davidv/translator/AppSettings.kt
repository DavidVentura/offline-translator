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

object Constants {
  const val DEFAULT_CATALOG_INDEX_URL = "https://offline-translator.davidv.dev/index.json"
}

data class AppSettings(
  val defaultTargetLanguageCode: String = "en",
  val defaultSourceLanguageCode: String? = null,
  val catalogIndexUrl: String = Constants.DEFAULT_CATALOG_INDEX_URL,
  val backgroundMode: BackgroundMode = BackgroundMode.AUTO_DETECT,
  val minConfidence: Int = 75,
  val maxImageSize: Int = 1500,
  val disableOcr: Boolean = false,
  val disableCLD: Boolean = false,
  val enableOutputTransliteration: Boolean = true,
  val useExternalStorage: Boolean = false,
  val fontFactor: Float = 1.0f,
  val showOCRDetection: Boolean = false,
  val showFilePickerInImagePicker: Boolean = false,
  val showTransliterationOnInput: Boolean = false,
  val onlyShowOutputOnReadonlyModal: Boolean = false,
  val readonlyModalOutputAlignment: ReadonlyModalOutputAlignment = ReadonlyModalOutputAlignment.MIDDLE,
  val addSpacesForJapaneseTransliteration: Boolean = true,
  val ttsPlaybackSpeed: Float = 1.0f,
  val ttsVoiceOverrides: Map<String, String> = emptyMap(),
)

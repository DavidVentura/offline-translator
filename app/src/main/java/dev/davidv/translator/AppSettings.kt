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

import androidx.annotation.StringRes

object Constants {
  const val DEFAULT_CATALOG_INDEX_URL = "https://offline-translator.davidv.dev/index_v7.json"
}

enum class AssistantAction(
  @StringRes val labelRes: Int,
) {
  STILL_IMAGE(R.string.assistant_action_still_image),
  LIVE_SCREEN(R.string.assistant_action_live_screen),
}

enum class HttpServerBindMode(
  @StringRes val labelRes: Int,
) {
  LOCALHOST(R.string.settings_http_server_bind_localhost),
  ALL_INTERFACES(R.string.settings_http_server_bind_all),
}

data class AppSettings(
  val defaultTargetLanguageCode: String = "en",
  val defaultSourceLanguageCode: String? = null,
  val catalogIndexUrl: String = Constants.DEFAULT_CATALOG_INDEX_URL,
  val backgroundMode: BackgroundMode = BackgroundMode.AUTO_DETECT,
  val minConfidence: Int = 75,
  val maxImageSize: Int = 1400,
  val disableCLD: Boolean = false,
  val experimentalScreenTranslate: Boolean = false,
  val enableOutputTransliteration: Boolean = true,
  val useExternalStorage: Boolean = false,
  val fontFactor: Float = 1.0f,
  val showTransliterationOnInput: Boolean = false,
  val onlyShowOutputOnReadonlyModal: Boolean = false,
  val readonlyModalOutputAlignment: ReadonlyModalOutputAlignment = ReadonlyModalOutputAlignment.MIDDLE,
  val readonlyPopupSize: ReadonlyPopupSize = ReadonlyPopupSize.DEFAULT,
  val mainInputSplit: InputSplit = InputSplit.DEFAULT,
  val overlayLanguages: OverlayLanguageMemory? = null,
  val addSpacesForJapaneseTransliteration: Boolean = true,
  val ttsPlaybackSpeed: Float = 1.0f,
  val ttsPlaybackSpeedOverrides: Map<String, Float> = emptyMap(),
  val ttsVoiceOverrides: Map<String, String> = emptyMap(),
  val ttsReadUrlsAndHashtags: Boolean = false,
  val tapToTranslateEnabled: Boolean = false,
  val clearWebTranslatorDataOnClose: Boolean = true,
  val translatePdfImages: Boolean = true,
  val liveCameraOverlayEnabled: Boolean = true,
  val registerAsBrowser: Boolean = true,
  val assistantAction: AssistantAction = AssistantAction.STILL_IMAGE,
  val multiTargetEnabled: Boolean = false,
  val httpServerEnabled: Boolean = false,
  val httpServerPort: Int = 5000,
  val httpServerBindMode: HttpServerBindMode = HttpServerBindMode.LOCALHOST,
)

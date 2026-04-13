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

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class SettingsManager(
  context: Context,
) {
  private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

  private val modifiedSettings = mutableSetOf<String>()

  private val _settings = MutableStateFlow(loadSettings())
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()

  private val prefsListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
      _settings.value = loadSettings()
    }

  init {
    prefs.registerOnSharedPreferenceChangeListener(prefsListener)
  }

  private fun loadSettings(): AppSettings {
    val defaults = AppSettings()

    modifiedSettings.clear()
    prefs.all.keys.forEach { key ->
      modifiedSettings.add(key)
    }

    val defaultTargetLanguageCode = prefs.getString("default_target_language", null) ?: defaults.defaultTargetLanguageCode
    val defaultSourceLanguageCode = prefs.getString("default_source_language", null)

    val catalogIndexUrl =
      prefs.getString("catalog_index_url", null)
        ?: defaults.catalogIndexUrl

    val backgroundModeName = prefs.getString("background_mode", null)
    val backgroundMode =
      if (backgroundModeName != null) {
        try {
          BackgroundMode.valueOf(backgroundModeName)
        } catch (_: IllegalArgumentException) {
          defaults.backgroundMode
        }
      } else {
        defaults.backgroundMode
      }

    val minConfidence = prefs.getInt("min_confidence", defaults.minConfidence)
    val maxImageSize = prefs.getInt("max_image_size", defaults.maxImageSize)
    val disableOcr = prefs.getBoolean("disable_ocr", defaults.disableOcr)
    val disableCLD = prefs.getBoolean("disable_cld", defaults.disableCLD)
    val enableOutputTransliteration = prefs.getBoolean("enable_output_transliteration", defaults.enableOutputTransliteration)
    val useExternalStorage = prefs.getBoolean("use_external_storage", defaults.useExternalStorage)
    val fontFactor = prefs.getFloat("font_factor", defaults.fontFactor)
    val showOCRDetection = prefs.getBoolean("show_ocr_detection", defaults.showOCRDetection)
    val showFilePickerInImagePicker = prefs.getBoolean("show_file_picker_in_image_picker", defaults.showFilePickerInImagePicker)
    val showTransliterationOnInput = prefs.getBoolean("show_transliteration_on_input", defaults.showTransliterationOnInput)
    val onlyShowOutputOnReadonlyModal =
      prefs.getBoolean(
        "only_show_output_on_readonly_modal",
        defaults.onlyShowOutputOnReadonlyModal,
      )
    val readonlyModalOutputAlignmentName = prefs.getString("readonly_modal_output_alignment", null)
    val readonlyModalOutputAlignment =
      if (readonlyModalOutputAlignmentName != null) {
        try {
          ReadonlyModalOutputAlignment.valueOf(readonlyModalOutputAlignmentName)
        } catch (_: IllegalArgumentException) {
          defaults.readonlyModalOutputAlignment
        }
      } else {
        defaults.readonlyModalOutputAlignment
      }
    val addSpacesForJapaneseTransliteration =
      prefs.getBoolean(
        "add_spaces_for_japanese_transliteration",
        defaults.addSpacesForJapaneseTransliteration,
      )
    val ttsPlaybackSpeed = prefs.getFloat("tts_playback_speed", defaults.ttsPlaybackSpeed)
    val ttsVoiceOverrides =
      prefs
        .getString("tts_voice_overrides", null)
        ?.let(::parseVoiceOverrides)
        ?: defaults.ttsVoiceOverrides

    return AppSettings(
      defaultTargetLanguageCode = defaultTargetLanguageCode,
      defaultSourceLanguageCode = defaultSourceLanguageCode,
      catalogIndexUrl = catalogIndexUrl,
      backgroundMode = backgroundMode,
      minConfidence = minConfidence,
      maxImageSize = maxImageSize,
      disableOcr = disableOcr,
      disableCLD = disableCLD,
      enableOutputTransliteration = enableOutputTransliteration,
      useExternalStorage = useExternalStorage,
      fontFactor = fontFactor,
      showOCRDetection = showOCRDetection,
      showFilePickerInImagePicker = showFilePickerInImagePicker,
      showTransliterationOnInput = showTransliterationOnInput,
      onlyShowOutputOnReadonlyModal = onlyShowOutputOnReadonlyModal,
      readonlyModalOutputAlignment = readonlyModalOutputAlignment,
      addSpacesForJapaneseTransliteration = addSpacesForJapaneseTransliteration,
      ttsPlaybackSpeed = ttsPlaybackSpeed,
      ttsVoiceOverrides = ttsVoiceOverrides,
    )
  }

  fun updateSettings(newSettings: AppSettings) {
    val currentSettings = _settings.value

    prefs.edit().apply {
      if (newSettings.defaultTargetLanguageCode != currentSettings.defaultTargetLanguageCode) {
        putString("default_target_language", newSettings.defaultTargetLanguageCode)
        modifiedSettings.add("default_target_language")
      }
      if (newSettings.defaultSourceLanguageCode != currentSettings.defaultSourceLanguageCode) {
        if (newSettings.defaultSourceLanguageCode != null) {
          putString("default_source_language", newSettings.defaultSourceLanguageCode)
        } else {
          remove("default_source_language")
        }
        modifiedSettings.add("default_source_language")
      }
      if (newSettings.catalogIndexUrl != currentSettings.catalogIndexUrl) {
        putString("catalog_index_url", newSettings.catalogIndexUrl)
        modifiedSettings.add("catalog_index_url")
      }
      if (newSettings.backgroundMode != currentSettings.backgroundMode) {
        putString("background_mode", newSettings.backgroundMode.name)
        modifiedSettings.add("background_mode")
      }
      if (newSettings.minConfidence != currentSettings.minConfidence) {
        putInt("min_confidence", newSettings.minConfidence)
        modifiedSettings.add("min_confidence")
      }
      if (newSettings.maxImageSize != currentSettings.maxImageSize) {
        putInt("max_image_size", newSettings.maxImageSize)
        modifiedSettings.add("max_image_size")
      }
      if (newSettings.disableOcr != currentSettings.disableOcr) {
        putBoolean("disable_ocr", newSettings.disableOcr)
        modifiedSettings.add("disable_ocr")
      }
      if (newSettings.disableCLD != currentSettings.disableCLD) {
        putBoolean("disable_cld", newSettings.disableCLD)
        modifiedSettings.add("disable_cld")
      }
      if (newSettings.enableOutputTransliteration != currentSettings.enableOutputTransliteration) {
        putBoolean("enable_output_transliteration", newSettings.enableOutputTransliteration)
        modifiedSettings.add("enable_output_transliteration")
      }
      if (newSettings.useExternalStorage != currentSettings.useExternalStorage) {
        putBoolean("use_external_storage", newSettings.useExternalStorage)
        modifiedSettings.add("use_external_storage")
      }
      if (newSettings.showOCRDetection != currentSettings.showOCRDetection) {
        putBoolean("show_ocr_detection", newSettings.showOCRDetection)
        modifiedSettings.add("show_ocr_detection")
      }
      if (newSettings.fontFactor != currentSettings.fontFactor) {
        putFloat("font_factor", newSettings.fontFactor)
        modifiedSettings.add("font_factor")
      }
      if (newSettings.showFilePickerInImagePicker != currentSettings.showFilePickerInImagePicker) {
        putBoolean("show_file_picker_in_image_picker", newSettings.showFilePickerInImagePicker)
        modifiedSettings.add("show_file_picker_in_image_picker")
      }
      if (newSettings.showTransliterationOnInput != currentSettings.showTransliterationOnInput) {
        putBoolean("show_transliteration_on_input", newSettings.showTransliterationOnInput)
        modifiedSettings.add("show_transliteration_on_input")
      }
      if (newSettings.onlyShowOutputOnReadonlyModal != currentSettings.onlyShowOutputOnReadonlyModal) {
        putBoolean("only_show_output_on_readonly_modal", newSettings.onlyShowOutputOnReadonlyModal)
        modifiedSettings.add("only_show_output_on_readonly_modal")
      }
      if (newSettings.readonlyModalOutputAlignment != currentSettings.readonlyModalOutputAlignment) {
        putString("readonly_modal_output_alignment", newSettings.readonlyModalOutputAlignment.name)
        modifiedSettings.add("readonly_modal_output_alignment")
      }
      if (newSettings.addSpacesForJapaneseTransliteration != currentSettings.addSpacesForJapaneseTransliteration) {
        putBoolean("add_spaces_for_japanese_transliteration", newSettings.addSpacesForJapaneseTransliteration)
        modifiedSettings.add("add_spaces_for_japanese_transliteration")
      }
      if (newSettings.ttsPlaybackSpeed != currentSettings.ttsPlaybackSpeed) {
        putFloat("tts_playback_speed", newSettings.ttsPlaybackSpeed)
        modifiedSettings.add("tts_playback_speed")
      }
      if (newSettings.ttsVoiceOverrides != currentSettings.ttsVoiceOverrides) {
        putString("tts_voice_overrides", serializeVoiceOverrides(newSettings.ttsVoiceOverrides))
        modifiedSettings.add("tts_voice_overrides")
      }
      remove("translation_models_base_url_v3")
      remove("tesseract_models_base_url")
      remove("dictionary_base_url")
      apply()
    }
    _settings.value = newSettings
  }

  private fun parseVoiceOverrides(json: String): Map<String, String> =
    runCatching {
      val root = JSONObject(json)
      root.keys().asSequence().mapNotNull { languageCode ->
        root.optString(languageCode).takeIf { it.isNotBlank() }?.let { languageCode to it }
      }.toMap()
    }.getOrDefault(emptyMap())

  private fun serializeVoiceOverrides(overrides: Map<String, String>): String {
    val root = JSONObject()
    overrides.toSortedMap().forEach { (languageCode, voiceName) ->
      root.put(languageCode, voiceName)
    }
    return root.toString()
  }
}

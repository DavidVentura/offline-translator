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

class SettingsManager(
  context: Context,
) {
  private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

  // Track which settings have been explicitly modified - initialize first
  private val modifiedSettings = mutableSetOf<String>()

  private val _settings = MutableStateFlow(loadSettings())
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()

  private fun loadSettings(): AppSettings {
    val defaults = AppSettings() // Get current defaults

    // Build set of modified settings from SharedPreferences
    modifiedSettings.clear()
    prefs.all.keys.forEach { key ->
      modifiedSettings.add(key)
    }

    val defaultTargetLanguageCode = prefs.getString("default_target_language", null)
    val defaultTargetLanguage =
      if (defaultTargetLanguageCode != null) {
        Language.entries.find { it.code == defaultTargetLanguageCode } ?: defaults.defaultTargetLanguage
      } else {
        defaults.defaultTargetLanguage
      }

    val defaultSourceLanguageCode = prefs.getString("default_source_language", null)
    val defaultSourceLanguage =
      if (defaultSourceLanguageCode != null) {
        Language.entries.find { it.code == defaultSourceLanguageCode }
      } else {
        defaults.defaultSourceLanguage
      }

    val translationModelsBaseUrl =
      prefs.getString("translation_models_base_url_v3", null)
        ?: defaults.translationModelsBaseUrl

    val tesseractModelsBaseUrl =
      prefs.getString("tesseract_models_base_url", null)
        ?: defaults.tesseractModelsBaseUrl

    val dictionaryBaseUrl =
      prefs.getString("dictionary_base_url", null)
        ?: defaults.dictionaryBaseUrl

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
    val addSpacesForJapaneseTransliteration =
      prefs.getBoolean(
        "add_spaces_for_japanese_transliteration",
        defaults.addSpacesForJapaneseTransliteration,
      )

    return AppSettings(
      defaultTargetLanguage = defaultTargetLanguage,
      defaultSourceLanguage = defaultSourceLanguage,
      translationModelsBaseUrl = translationModelsBaseUrl,
      tesseractModelsBaseUrl = tesseractModelsBaseUrl,
      dictionaryBaseUrl = dictionaryBaseUrl,
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
      addSpacesForJapaneseTransliteration = addSpacesForJapaneseTransliteration,
    )
  }

  fun updateSettings(newSettings: AppSettings) {
    val currentSettings = _settings.value

    prefs.edit().apply {
      // Only save settings that have changed from their current value
      if (newSettings.defaultTargetLanguage != currentSettings.defaultTargetLanguage) {
        putString("default_target_language", newSettings.defaultTargetLanguage.code)
        modifiedSettings.add("default_target_language")
      }
      if (newSettings.defaultSourceLanguage != currentSettings.defaultSourceLanguage) {
        if (newSettings.defaultSourceLanguage != null) {
          putString("default_source_language", newSettings.defaultSourceLanguage.code)
        } else {
          remove("default_source_language")
        }
        modifiedSettings.add("default_source_language")
      }
      if (newSettings.translationModelsBaseUrl != currentSettings.translationModelsBaseUrl) {
        putString("translation_models_base_url_v3", newSettings.translationModelsBaseUrl)
        modifiedSettings.add("translation_models_base_url_v3")
      }
      if (newSettings.tesseractModelsBaseUrl != currentSettings.tesseractModelsBaseUrl) {
        putString("tesseract_models_base_url", newSettings.tesseractModelsBaseUrl)
        modifiedSettings.add("tesseract_models_base_url")
      }
      if (newSettings.dictionaryBaseUrl != currentSettings.dictionaryBaseUrl) {
        putString("dictionary_base_url", newSettings.dictionaryBaseUrl)
        modifiedSettings.add("dictionary_base_url")
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
      if (newSettings.addSpacesForJapaneseTransliteration != currentSettings.addSpacesForJapaneseTransliteration) {
        putBoolean("add_spaces_for_japanese_transliteration", newSettings.addSpacesForJapaneseTransliteration)
        modifiedSettings.add("add_spaces_for_japanese_transliteration")
      }
      apply()
    }
    _settings.value = newSettings
  }
}

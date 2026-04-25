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

import android.app.Application
import android.util.Log
import dev.davidv.translator.adblock.AdblockManager

class TranslatorApplication : Application() {
  lateinit var settingsManager: SettingsManager
  lateinit var languageMetadataManager: LanguageMetadataManager
  lateinit var filePathManager: FilePathManager
  lateinit var imageProcessor: ImageProcessor
  lateinit var translationService: TranslationService
  lateinit var speechService: SpeechService
  lateinit var languageDetector: LanguageDetector
  lateinit var translationCoordinator: TranslationCoordinator
  lateinit var adblockManager: AdblockManager
  val languagesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Language>>(emptyList())
  var languageCatalog: LanguageCatalog? = null
    private set

  override fun onCreate() {
    super.onCreate()
    Log.d("TranslatorApplication", "Initializing application services")

    settingsManager = SettingsManager(this)
    filePathManager = FilePathManager(this, settingsManager.settings)
    languageCatalog = filePathManager.loadCatalog()
    languagesFlow.value = languageCatalog?.languageList ?: emptyList()
    languageMetadataManager = LanguageMetadataManager(this, languagesFlow)
    imageProcessor = ImageProcessor(this, filePathManager)
    translationService = TranslationService(settingsManager, filePathManager)
    speechService = SpeechService(settingsManager, filePathManager)
    languageDetector = LanguageDetector { code -> languageCatalog?.languageByCode(code) }
    translationCoordinator =
      TranslationCoordinator(translationService, speechService, languageDetector, imageProcessor, settingsManager)
    adblockManager = AdblockManager(filePathManager)

    if (settingsManager.settings.value.tapToTranslateEnabled) {
      TapToTranslateNotification.show(this)
    }
  }
}

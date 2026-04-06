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

class TranslatorApplication : Application() {
  lateinit var settingsManager: SettingsManager
  lateinit var languageMetadataManager: LanguageMetadataManager
  lateinit var filePathManager: FilePathManager
  lateinit var ocrService: OCRService
  lateinit var imageProcessor: ImageProcessor
  lateinit var translationService: TranslationService
  lateinit var languageDetector: LanguageDetector
  lateinit var translationCoordinator: TranslationCoordinator

  override fun onCreate() {
    super.onCreate()
    Log.d("TranslatorApplication", "Initializing application services")

    settingsManager = SettingsManager(this)
    languageMetadataManager = LanguageMetadataManager(this)
    filePathManager = FilePathManager(this, settingsManager.settings)
    ocrService = OCRService(filePathManager)
    imageProcessor = ImageProcessor(this, ocrService)
    translationService = TranslationService(settingsManager, filePathManager)
    languageDetector = LanguageDetector()
    translationCoordinator =
      TranslationCoordinator(translationService, languageDetector, imageProcessor, settingsManager)
  }
}

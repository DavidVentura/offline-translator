package dev.davidv.translator.assistantOverlay

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.davidv.translator.BatchTextTranslator
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.ImageProcessor
import dev.davidv.translator.LanguageDetector
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.OCRService
import dev.davidv.translator.OverlayTextTranslationHelper
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class TranslatorVoiceInteractionSessionService : VoiceInteractionSessionService() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private lateinit var settingsManager: SettingsManager
  private lateinit var filePathManager: FilePathManager
  private lateinit var languageMetadataManager: LanguageMetadataManager
  private lateinit var ocrService: OCRService
  private lateinit var imageProcessor: ImageProcessor
  private lateinit var translationCoordinator: TranslationCoordinator
  private lateinit var overlayTextTranslationHelper: OverlayTextTranslationHelper
  private lateinit var langStateManager: LanguageStateManager

  override fun onCreate() {
    super.onCreate()
    settingsManager = SettingsManager(this)
    filePathManager = FilePathManager(this, settingsManager.settings)
    langStateManager = LanguageStateManager(serviceScope, filePathManager, null)
    val catalog = filePathManager.loadCatalog()
    val languagesFlow = kotlinx.coroutines.flow.MutableStateFlow(catalog?.languageList ?: emptyList())
    languageMetadataManager = LanguageMetadataManager(this, languagesFlow)
    ocrService = OCRService(filePathManager)
    imageProcessor = ImageProcessor(this, ocrService)
    val english = catalog?.english ?: dev.davidv.translator.Language(code = "en", displayName = "English", shortDisplayName = "EN", tessName = "eng", script = "Latn", dictionaryCode = "en", tessdataSizeBytes = 0, toEnglish = null, fromEnglish = null, extraFiles = emptyList())
    translationCoordinator =
      TranslationCoordinator(
        translationService = TranslationService(settingsManager, filePathManager, english),
        languageDetector = LanguageDetector(langStateManager::languageByCode),
        imageProcessor = imageProcessor,
        settingsManager = settingsManager,
      )
    overlayTextTranslationHelper =
      OverlayTextTranslationHelper(
        settingsManager = settingsManager,
        batchTextTranslator = BatchTextTranslator(translationCoordinator),
        langStateManager = langStateManager,
        languageMetadataManager = languageMetadataManager,
      )
  }

  override fun onNewSession(args: Bundle?): VoiceInteractionSession =
    TranslatorVoiceInteractionSession(
      context = this,
      settingsManager = settingsManager,
      imageProcessor = imageProcessor,
      translationCoordinator = translationCoordinator,
      overlayTextTranslationHelper = overlayTextTranslationHelper,
      langStateManager = langStateManager,
    )

  override fun onDestroy() {
    if (this::ocrService.isInitialized) {
      ocrService.cleanup()
    }
    serviceScope.cancel()
    super.onDestroy()
  }
}

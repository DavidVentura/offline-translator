package dev.davidv.translator

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AidlTranslationService : Service() {
  private val tag = this.javaClass.name.substringAfterLast('.')

  private lateinit var settingsManager: SettingsManager
  private lateinit var translationCoordinator: TranslationCoordinator
  private lateinit var langStateManager: LanguageStateManager
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun onCreate() {
    super.onCreate()
    settingsManager = SettingsManager(this)
    val filePathManager = FilePathManager(this, settingsManager.settings)
    langStateManager = LanguageStateManager(serviceScope, filePathManager, null)
    val languageDetector = LanguageDetector(langStateManager::languageByCode)
    val imageProcessor = ImageProcessor(this, filePathManager)

    serviceScope.launch {
      langStateManager.catalog.collect { catalog ->
        if (catalog == null) return@collect
        val translationService = TranslationService(settingsManager, filePathManager)
        val speechService = SpeechService(settingsManager, filePathManager)
        translationCoordinator = TranslationCoordinator(translationService, speechService, languageDetector, imageProcessor, settingsManager)
        Log.d(tag, "TranslationCoordinator initialized")
      }
    }
    Log.d(tag, "onCreate")
  }

  override fun onBind(intent: Intent?): IBinder {
    Log.d(tag, "onBind")
    return binder
  }

  private val binder =
    object : ITranslationService.Stub() {
      override fun translate(
        textToTranslate: String?,
        fromLanguageStr: String?,
        toLanguageStr: String?,
        callback: ITranslationCallback?,
      ) {
        Log.d(tag, "txt len:${textToTranslate?.length ?: -1}, from:$fromLanguageStr, to:$toLanguageStr, cb = ${callback != null}")

        if (textToTranslate == null || callback == null) {
          Log.w(tag, "translate: textToTranslate or callback is null")
          return
        }

        val fromLanguage = fromLanguageStr?.takeIf { it.isNotEmpty() }?.let { langStateManager.languageByCode(it) }
        val toLanguage = toLanguageStr?.takeIf { it.isNotEmpty() }?.let { langStateManager.languageByCode(it) }

        CoroutineScope(Dispatchers.IO).launch {
          langStateManager.languageState.first { !it.isChecking }
          val langs = langStateManager.languageState.value.translatorLanguages()
          while (translationCoordinator.isTranslating.value) {
            delay(100)
          }
          val from = fromLanguage ?: translationCoordinator.detectLanguageRobust(textToTranslate, null, langs)
          Log.d(tag, "Detected lang $from")
          if (from == null) {
            Log.d(tag, "Could not detect language")
            val err =
              TranslationError().apply {
                type = ErrorType.COULD_NOT_DETECT_LANGUAGE
                language = null
                message = null
              }
            callback.onTranslationError(err)
            return@launch
          }
          if (!langs.contains(from)) {
            Log.d(tag, "Detected language ${from.displayName} not available")
            val err =
              TranslationError().apply {
                type = ErrorType.DETECTED_BUT_UNAVAILABLE
                language = from.displayName
                message = null
              }
            callback.onTranslationError(err)
            return@launch
          }
          val to = toLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode)
          if (to == null) {
            val err =
              TranslationError().apply {
                type = ErrorType.UNEXPECTED
                language = null
                message = getString(R.string.aidl_target_unavailable)
              }
            callback.onTranslationError(err)
            return@launch
          }
          when (val result = translationCoordinator.translateText(from, to, textToTranslate)) {
            is TranslationResult.Success -> {
              val translatedText = result.result.translated
              Log.d(tag, "translated text: $translatedText")
              callback.onTranslationResult(translatedText)
            }

            is TranslationResult.Error -> {
              Log.d(tag, "Translation error: ${result.message}")
              val err =
                TranslationError().apply {
                  type = ErrorType.UNEXPECTED
                  language = null
                  message = result.message
                }
              callback.onTranslationError(err)
            }
          }
        }
      }

      override fun translateImage(
        image: ParcelFileDescriptor?,
        fromLanguageStr: String?,
        toLanguageStr: String?,
        callback: IImageTranslationCallback?,
      ) {
        Log.d(tag, "translateImage from:$fromLanguageStr, to:$toLanguageStr, cb = ${callback != null}")

        if (image == null || callback == null) {
          Log.w(tag, "translateImage: image or callback is null")
          image?.close()
          return
        }

        val forcedFrom = fromLanguageStr?.takeIf { it.isNotEmpty() }?.let { langStateManager.languageByCode(it) }
        val toLanguage = toLanguageStr?.takeIf { it.isNotEmpty() }?.let { langStateManager.languageByCode(it) }

        CoroutineScope(Dispatchers.IO).launch {
          try {
            val bitmap =
              ParcelFileDescriptor.AutoCloseInputStream(image).use { input ->
                BitmapFactory.decodeStream(input)
              }
            if (bitmap == null) {
              callback.onImageError(ErrorType.UNEXPECTED, null, "Could not decode image")
              return@launch
            }

            langStateManager.languageState.first { !it.isChecking }
            while (translationCoordinator.isTranslating.value) {
              delay(100)
            }

            val to = toLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode)
            if (to == null) {
              callback.onImageError(ErrorType.UNEXPECTED, null, "Target language not available")
              return@launch
            }

            val isAutoSource = forcedFrom == null
            var missingDetected: Language? = null
            val result =
              translationCoordinator.translateImageWithOverlay(
                from = forcedFrom ?: to,
                to = to,
                finalBitmap = bitmap,
                onMessage = {},
                isAutoSource = isAutoSource,
                onMissingDetectedLanguage = { missingDetected = it },
              )

            if (result == null) {
              val missing = missingDetected
              if (missing != null) {
                callback.onImageError(ErrorType.DETECTED_BUT_UNAVAILABLE, missing.displayName, null)
              } else {
                callback.onImageError(ErrorType.UNEXPECTED, null, "Image translation failed")
              }
              return@launch
            }

            val textLines =
              result.metadata.blocks.flatMap { block ->
                block.lines.mapIndexed { i, line ->
                  TextLineResult().apply {
                    sourceText = line.text
                    translatedText = if (i == 0) block.translatedText else ""
                    left = line.boundingBox.left.toInt()
                    top = line.boundingBox.top.toInt()
                    right = line.boundingBox.right.toInt()
                    bottom = line.boundingBox.bottom.toInt()
                    orientedCenterX = line.orientedBox.cx
                    orientedCenterY = line.orientedBox.cy
                    orientedWidth = line.orientedBox.width
                    orientedHeight = line.orientedBox.height
                    orientedAngleRadians = line.orientedBox.angleRadians
                    suggestedFontSizePx = block.layoutHints.suggestedFontSizePx
                    backgroundArgb = line.backgroundArgb.toInt()
                    foregroundArgb = line.foregroundArgb.toInt()
                  }
                }
              }
            val out =
              ImageTranslationResult().apply {
                extractedText = result.metadata.extractedText
                this.translatedText = result.metadata.translatedText
                this.textLines = textLines
              }
            callback.onResult(out)
          } catch (e: Exception) {
            Log.e(tag, "translateImage failed", e)
            callback.onImageError(ErrorType.UNEXPECTED, null, e.message)
          }
        }
      }

      private fun IImageTranslationCallback.onImageError(
        errorType: Byte,
        language: String?,
        message: String?,
      ) {
        onError(
          TranslationError().apply {
            type = errorType
            this.language = language
            this.message = message
          },
        )
      }
    }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    Log.d(tag, "onStartCommand received, but this service is meant to be bound.")
    return START_NOT_STICKY
  }

  override fun onUnbind(intent: Intent?): Boolean {
    Log.d(tag, "onUnbind")
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    Log.d(tag, "onDestroy")
    serviceScope.cancel()
    super.onDestroy()
  }
}

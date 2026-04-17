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

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.translator.PreparedImageOverlay
import java.nio.ByteBuffer
import kotlin.system.measureTimeMillis

class TranslationCoordinator(
  private val translationService: TranslationService,
  private val speechService: SpeechService,
  private val languageDetector: LanguageDetector,
  private val imageProcessor: ImageProcessor,
  private val settingsManager: SettingsManager,
) {
  private val _isTranslating = MutableStateFlow(false)
  val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

  private val _isOcrInProgress = MutableStateFlow(false)
  val isOcrInProgress: StateFlow<Boolean> = _isOcrInProgress.asStateFlow()

  var lastTranslatedInput: String = ""

  suspend fun preloadModel(
    from: Language,
    to: Language,
  ) {
    if (_isTranslating.value) {
      return
    }
    translationService.preloadModel(from, to)
  }

  suspend fun translateText(
    from: Language,
    to: Language,
    text: String,
  ): TranslationResult {
    if (text.isBlank()) return TranslationResult.Success(TranslatedText("", ""))

    _isTranslating.value = true
    val result: TranslationResult
    try {
      val elapsed =
        measureTimeMillis {
          result = translationService.translate(from, to, text)
        }
      Log.d("TranslationCoordinator", "Translating ${text.length} chars from ${from.displayName} to ${to.displayName} took ${elapsed}ms")
    } finally {
      lastTranslatedInput = text
      _isTranslating.value = false
    }
    return result
  }

  suspend fun detectLanguage(
    text: String,
    hint: Language?,
  ): Language? = languageDetector.detectLanguage(text, hint)

  suspend fun detectLanguageRobust(
    text: String,
    hint: Language?,
    availableLanguages: List<Language>,
  ): Language? = languageDetector.detectLanguageRobust(text, hint, availableLanguages)

  suspend fun translateStructuredFragments(
    fragments: List<StyledFragment>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
    screenshot: Bitmap?,
  ): StructuredFragmentTranslationOutput {
    if (fragments.isEmpty()) {
      return StructuredFragmentTranslationOutput.NothingToTranslate(NothingReason.NO_TRANSLATABLE_TEXT)
    }

    _isTranslating.value = true
    val result: StructuredFragmentTranslationOutput
    try {
      val elapsed =
        measureTimeMillis {
          result =
            translationService.translateStructuredFragments(
              fragments = fragments,
              forcedSourceLanguage = forcedSourceLanguage,
              targetLanguage = targetLanguage,
              availableLanguages = availableLanguages,
              screenshot = screenshot,
            )
        }
      Log.d("TranslationCoordinator", "Structured fragment translation of ${fragments.size} fragments took ${elapsed}ms")
    } finally {
      lastTranslatedInput = fragments.lastOrNull()?.text ?: ""
      _isTranslating.value = false
    }
    return result
  }

  suspend fun correctBitmap(
    uri: Uri,
    deleteAfterLoad: Boolean = false,
  ): Bitmap =
    withContext(Dispatchers.IO) {
      try {
        val originalBitmap = imageProcessor.loadBitmapFromUri(uri)
        val correctedBitmap = imageProcessor.correctImageOrientation(uri, originalBitmap)

        if (correctedBitmap !== originalBitmap && !originalBitmap.isRecycled) {
          originalBitmap.recycle()
        }

        val maxImageSize = settingsManager.settings.value.maxImageSize
        val finalBitmap = imageProcessor.downscaleImage(correctedBitmap, maxImageSize)

        if (finalBitmap !== correctedBitmap && !correctedBitmap.isRecycled) {
          correctedBitmap.recycle()
        }

        finalBitmap
      } finally {
        if (deleteAfterLoad) {
          imageProcessor.deleteTemporaryImageUri(uri)
        }
      }
    }

  suspend fun translateImageWithOverlay(
    from: Language,
    to: Language,
    finalBitmap: Bitmap,
    onMessage: (TranslatorMessage.ImageTextDetected) -> Unit,
    readingOrder: ReadingOrder = ReadingOrder.LEFT_TO_RIGHT,
  ): ProcessedImageResult? =
    withContext(Dispatchers.IO) {
      _isTranslating.value = true
      try {
        _isOcrInProgress.value = true
        val catalog = imageProcessor.loadCatalog() ?: return@withContext null
        val minConfidence = settingsManager.settings.value.minConfidence
        val backgroundMode = settingsManager.settings.value.backgroundMode
        val plan =
          try {
            catalog.translateImagePlan(finalBitmap, from, to, minConfidence, readingOrder, backgroundMode)
          } catch (e: uniffi.bindings.CatalogException) {
            Log.d("OCR", "translateImagePlan failed: ${e.message}")
            return@withContext null
          }
        _isOcrInProgress.value = false

        Log.d("OCR", "complete, blocks=${plan.blocks.size}")

        val extractedText = plan.extractedText
        onMessage(TranslatorMessage.ImageTextDetected(extractedText))
        val erasedBitmap = bitmapFromRgbaPlan(plan) ?: return@withContext null
        lateinit var overlayBitmap: Bitmap
        val translatePaint =
          measureTimeMillis {
            overlayBitmap =
              when (readingOrder) {
                ReadingOrder.LEFT_TO_RIGHT ->
                  paintTranslatedTextOver(erasedBitmap, plan.blocks).first
                ReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT ->
                  paintTranslatedTextOverVerticalBlocks(erasedBitmap, plan.blocks).first
              }
          }
        Log.i("TranslationCoordinator", "Overpainting took ${translatePaint}ms")

        ProcessedImageResult(
          correctedBitmap = overlayBitmap,
          extractedText = extractedText,
          translatedText = plan.translatedText,
          metadata = plan,
        )
      } catch (e: Exception) {
        Log.e("TranslationCoordinator", "Exception ${e.stackTrace}")
        null
      } finally {
        _isOcrInProgress.value = false
        _isTranslating.value = false
      }
    }

  fun transliterate(
    text: String,
    from: Language,
  ): String? = translationService.transliterate(text, from)

  suspend fun synthesizeSpeech(
    language: Language,
    text: String,
  ): SpeechSynthesisResult = speechService.synthesizeSpeech(language, text)

  suspend fun availableTtsVoices(language: Language): List<TtsVoiceOption> = speechService.availableTtsVoices(language)

  private fun bitmapFromRgbaPlan(plan: PreparedImageOverlay): Bitmap? {
    return try {
      Bitmap.createBitmap(plan.width.toInt(), plan.height.toInt(), Bitmap.Config.ARGB_8888).apply {
        copyPixelsFromBuffer(ByteBuffer.wrap(plan.rgbaBytes))
      }
    } catch (e: Exception) {
      Log.e("TranslationCoordinator", "Failed to decode erased OCR bitmap", e)
      null
    }
  }
}

data class ProcessedImageResult(
  val correctedBitmap: Bitmap,
  val extractedText: String,
  val translatedText: String,
  val metadata: PreparedImageOverlay,
)

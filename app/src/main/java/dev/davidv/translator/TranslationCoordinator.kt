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
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.system.measureTimeMillis

class TranslationCoordinator(
  private val context: Context,
  val translationService: TranslationService,
  private val languageDetector: LanguageDetector,
  private val imageProcessor: ImageProcessor,
  private val settingsManager: SettingsManager,
) {
  private val _isTranslating = MutableStateFlow(false)
  val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

  private val _isOcrInProgress = MutableStateFlow(false)
  val isOcrInProgress: StateFlow<Boolean> = _isOcrInProgress.asStateFlow()

  suspend fun translateText(
    from: Language,
    to: Language,
    text: String,
  ): TranslationResult? {
    if (text.isBlank()) return null

    _isTranslating.value = true
    val result: TranslationResult
    try {
      val elapsed =
        measureTimeMillis {
          result = translationService.translate(from, to, text)
        }
      Log.d("TranslationCoordinator", "Translating ${text.length} chars from ${from.displayName} to ${to.displayName} took ${elapsed}ms")
    } finally {
      _isTranslating.value = false
    }
    return when (result) {
      is TranslationResult.Success -> result
      is TranslationResult.Error -> {
        Toast
          .makeText(
            context,
            "Translation error: ${result.message}",
            Toast.LENGTH_SHORT,
          ).show()
        null
      }
    }
  }

  suspend fun detectLanguage(
    text: String,
    hint: Language?,
  ): Language? = languageDetector.detectLanguage(text, hint)

  suspend fun detectLanguageRobust(
    text: String,
    hint: Language?,
  ): Language? = languageDetector.detectLanguageRobust(text, hint, translationService.filePathManager)

  fun correctBitmap(uri: Uri): Bitmap {
    val originalBitmap = imageProcessor.loadBitmapFromUri(uri)
    val correctedBitmap = imageProcessor.correctImageOrientation(uri, originalBitmap)

    // Recycle original if it's different from corrected
    if (correctedBitmap !== originalBitmap && !originalBitmap.isRecycled) {
      originalBitmap.recycle()
    }

    val maxImageSize = settingsManager.settings.value.maxImageSize
    val finalBitmap = imageProcessor.downscaleImage(correctedBitmap, maxImageSize)

    // Recycle corrected if it's different from final
    if (finalBitmap !== correctedBitmap && !correctedBitmap.isRecycled) {
      correctedBitmap.recycle()
    }

    return finalBitmap
  }

  suspend fun translateImageWithOverlay(
    from: Language,
    to: Language,
    finalBitmap: Bitmap,
    onMessage: (TranslatorMessage.ImageTextDetected) -> Unit,
  ): ProcessedImageResult? {
    _isTranslating.value = true
    return try {
      _isOcrInProgress.value = true
      val minConfidence = settingsManager.settings.value.minConfidence
      val processedImage = imageProcessor.processImage(finalBitmap, minConfidence)
      _isOcrInProgress.value = false

      // Create translation function for overlay
      suspend fun translateFn(text: String): String =
        when (val result = translationService.translate(from, to, text)) {
          is TranslationResult.Success -> result.result.translated
          is TranslationResult.Error -> {
            Toast
              .makeText(
                context,
                "Translation error: ${result.message}",
                Toast.LENGTH_SHORT,
              ).show()
            "Error"
          }
        }

      Log.d("OCR", "complete, result ${processedImage.textBlocks}")

      val extractedText =
        processedImage.textBlocks
          .map { block ->
            block.lines.map { line -> line.text }
          }.flatten()
          .joinToString("\n")

      onMessage(TranslatorMessage.ImageTextDetected(extractedText))

      // Paint translated text over image
      val overlayBitmap: Bitmap
      val allTranslatedText: String
      val translatePaint =
        measureTimeMillis {
          val pair =
            paintTranslatedTextOver(
              processedImage.bitmap,
              processedImage.textBlocks,
              ::translateFn,
              settingsManager.settings.value.backgroundMode,
            )
          overlayBitmap = pair.first
          allTranslatedText = pair.second
        }

      Log.i("TranslationCoordinator", "Translating and overpainting took ${translatePaint}ms")

      ProcessedImageResult(
        correctedBitmap = overlayBitmap,
        extractedText = extractedText,
        translatedText = allTranslatedText,
      )
    } catch (e: Exception) {
      Log.e("TranslationCoordinator", "Exception ${e.stackTrace}")
      Toast
        .makeText(context, "Image processing error: ${e.message}", Toast.LENGTH_SHORT)
        .show()
      null
    } finally {
      _isOcrInProgress.value = false
      _isTranslating.value = false
    }
  }
}

data class ProcessedImageResult(
  val correctedBitmap: Bitmap,
  val extractedText: String,
  val translatedText: String,
)

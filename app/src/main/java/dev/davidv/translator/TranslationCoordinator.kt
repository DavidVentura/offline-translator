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
  private val translationService: TranslationService,
  private val languageDetector: LanguageDetector,
  private val imageProcessor: ImageProcessor,
  private val settingsManager: SettingsManager,
  private val enableToast: Boolean = true,
) {
  private val _isTranslating = MutableStateFlow(false)
  val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

  private val _isOcrInProgress = MutableStateFlow(false)
  val isOcrInProgress: StateFlow<Boolean> = _isOcrInProgress.asStateFlow()

  var lastTranslatedInput: String = ""

  fun setMucabBinding(binding: MucabBinding?) {
    translationService.setMucabBinding(binding)
  }

  suspend fun preloadModel(
    from: Language,
    to: Language,
  ) {
    if (_isTranslating.value) {
      return
    }
    _isTranslating.value = true
    translationService.preloadModel(from, to)
    _isTranslating.value = false
  }

  suspend fun translateText(
    from: Language,
    to: Language,
    text: String,
  ): TranslationResult {
    if (text.isBlank()) return TranslationResult.Success(TranslatedText("", ""))
    // we do best-effort in TranslatorApp to not call this concurrently,
    // to avoid pointless queueing (only the latest result is useful)
    // but it's fine, there's a lock in the cpp code

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
    return when (result) {
      is TranslationResult.Success -> result
      is TranslationResult.Error -> {
        if (enableToast) {
          Toast
            .makeText(
              context,
              "Translation error: ${result.message}",
              Toast.LENGTH_SHORT,
            ).show()
        }
        result
      }
    }
  }

  suspend fun translateTexts(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): BatchTranslationResult {
    if (texts.isEmpty()) return BatchTranslationResult.Success(emptyList())

    _isTranslating.value = true
    val result: BatchTranslationResult
    try {
      val elapsed =
        measureTimeMillis {
          result = translationService.translateMultiple(from, to, texts)
        }
      Log.d("TranslationCoordinator", "Translating ${texts.size} texts from ${from.displayName} to ${to.displayName} took ${elapsed}ms")
    } finally {
      lastTranslatedInput = texts.lastOrNull() ?: ""
      _isTranslating.value = false
    }
    return when (result) {
      is BatchTranslationResult.Success -> result
      is BatchTranslationResult.Error -> {
        if (enableToast) {
          Toast
            .makeText(
              context,
              "Translation error: ${result.message}",
              Toast.LENGTH_SHORT,
            ).show()
        }
        result
      }
    }
  }

  suspend fun translateTextsWithAlignment(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): BatchAlignedTranslationResult {
    if (texts.isEmpty()) return BatchAlignedTranslationResult.Success(emptyList())

    _isTranslating.value = true
    val result: BatchAlignedTranslationResult
    try {
      val elapsed =
        measureTimeMillis {
          result = translationService.translateMultipleWithAlignment(from, to, texts)
        }
      Log.d("TranslationCoordinator", "Aligned translation of ${texts.size} texts took ${elapsed}ms")
    } finally {
      lastTranslatedInput = texts.lastOrNull() ?: ""
      _isTranslating.value = false
    }
    return when (result) {
      is BatchAlignedTranslationResult.Success -> result
      is BatchAlignedTranslationResult.Error -> {
        if (enableToast) {
          Toast
            .makeText(
              context,
              "Translation error: ${result.message}",
              Toast.LENGTH_SHORT,
            ).show()
        }
        result
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
    availableLanguages: List<Language>,
  ): Language? = languageDetector.detectLanguageRobust(text, hint, availableLanguages)

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
      val processedImage = imageProcessor.processImage(finalBitmap, from, minConfidence)
      _isOcrInProgress.value = false

      Log.d("OCR", "complete, result ${processedImage.textBlocks}")

      val extractedText =
        processedImage.textBlocks
          .map { block ->
            block.lines.map { line -> line.text }
          }.flatten()
          .joinToString("\n")

      onMessage(TranslatorMessage.ImageTextDetected(extractedText))

      val blockTexts = processedImage.textBlocks.map { it.lines.joinToString(" ") { line -> line.text } }
      val translatedBlocks: List<String>
      val totalTranslateMs =
        measureTimeMillis {
          when (val translationResult = translationService.translateMultiple(from, to, blockTexts.toTypedArray())) {
            is BatchTranslationResult.Success -> {
              translatedBlocks = translationResult.result.map { it.translated }
            }

            is BatchTranslationResult.Error -> {
              Toast
                .makeText(
                  context,
                  "Translation error: ${translationResult.message}",
                  Toast.LENGTH_SHORT,
                ).show()
              return null
            }
          }
        }
      Log.i("TranslationCoordinator", "Bulk translation took ${totalTranslateMs}ms")

      // Paint translated text over image
      val overlayBitmap: Bitmap
      val allTranslatedText: String
      val translatePaint =
        measureTimeMillis {
          val pair =
            paintTranslatedTextOver(
              processedImage.bitmap,
              processedImage.textBlocks,
              translatedBlocks,
              settingsManager.settings.value.backgroundMode,
            )
          overlayBitmap = pair.first
          allTranslatedText = pair.second
        }

      Log.i("TranslationCoordinator", "Overpainting took ${translatePaint}ms")

      ProcessedImageResult(
        correctedBitmap = overlayBitmap,
        extractedText = extractedText,
        translatedText = allTranslatedText,
      )
    } catch (e: Exception) {
      Log.e("TranslationCoordinator", "Exception ${e.stackTrace}")
      if (enableToast) {
        Toast
          .makeText(context, "Image processing error: ${e.message}", Toast.LENGTH_SHORT)
          .show()
      }
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
}

data class ProcessedImageResult(
  val correctedBitmap: Bitmap,
  val extractedText: String,
  val translatedText: String,
)

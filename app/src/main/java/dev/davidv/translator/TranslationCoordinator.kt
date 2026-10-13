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
import uniffi.translator_core.OcrSourceSelection
import uniffi.translator_core.PreparedImageOverlay
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
      Log.d("TranslationCoordinator", "Translation took ${elapsed}ms")
    } finally {
      lastTranslatedInput = text
      _isTranslating.value = false
    }
    return result
  }

  suspend fun steer(
    from: Language,
    to: Language,
    source: String,
    forcedPrefix: String,
  ): TranslationResult {
    _isTranslating.value = true
    return try {
      translationService.steer(from, to, source, forcedPrefix)
    } finally {
      _isTranslating.value = false
    }
  }

  /** Abort the in-flight document translation (fast, mid-batch). */
  fun cancelOngoingWork() = translationService.cancelOngoingWork()

  suspend fun translateDocumentPath(
    inputPath: String,
    outputPath: String,
    from: Language,
    to: Language,
    translatePdfImages: Boolean,
    txtLayout: TxtLayoutChoice,
    onProgress: (DocumentTranslationProgress) -> Unit = {},
    isCancelled: () -> Boolean = { false },
  ): Result<String> {
    _isTranslating.value = true
    try {
      val result =
        translationService.translateDocumentPath(
          inputPath = inputPath,
          outputPath = outputPath,
          from = from,
          to = to,
          translatePdfImages = translatePdfImages,
          txtLayout = txtLayout,
          onProgress = onProgress,
          isCancelled = isCancelled,
        )
      result.onSuccess { path ->
        Log.d("TranslationCoordinator", "Translated document to $path")
      }
      return result
    } finally {
      lastTranslatedInput = inputPath
      _isTranslating.value = false
    }
  }

  suspend fun detectLanguageRobust(
    text: String,
    hint: Language?,
    availableLanguages: List<Language>,
  ): Language? = languageDetector.detectLanguageRobust(text, hint, availableLanguages)

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

        correctedBitmap
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
    readingOrder: ReadingOrder? = null,
    isAutoSource: Boolean = false,
    onMissingDetectedLanguage: (Language) -> Unit = {},
    onOcrUnavailable: () -> Unit = {},
    onDetectedRegions: (List<uniffi.translator_core.OrientedRect>, Int, Int) -> Unit = { _, _, _ -> },
  ): ProcessedImageResult? =
    withContext(Dispatchers.IO) {
      _isTranslating.value = true
      val totalStart = System.currentTimeMillis()
      try {
        _isOcrInProgress.value = true
        val catalog = imageProcessor.loadCatalog() ?: return@withContext null
        val minConfidence = settingsManager.settings.value.minConfidence
        val backgroundMode = settingsManager.settings.value.backgroundMode
        val maxImageSize = settingsManager.settings.value.maxImageSize
        val sourceSelection =
          if (isAutoSource) {
            OcrSourceSelection.Auto
          } else {
            OcrSourceSelection.Specific(uniffi.translator_core.LanguageCode(from.code))
          }
        // Own the source pixels rust-side (one in-process copy via lockPixels, no byte[]/marshal),
        // then run detect → ocr → renderInto as separate cheap calls; the multi-MB image never
        // crosses the FFI boundary. `renderInto` composes the final overlay straight into the
        // display bitmap's locked pixels.
        val translateStart = System.currentTimeMillis()
        val srcAddr = NativeBitmap.lockPixels(finalBitmap)
        if (srcAddr == 0L) {
          Log.e("TranslationCoordinator", "lockPixels(source) failed")
          return@withContext null
        }
        val ocrImage =
          uniffi.bindings.OcrImage.fromPixels(
            srcAddr.toULong(),
            finalBitmap.width.toUInt(),
            finalBitmap.height.toUInt(),
          )
        NativeBitmap.unlockPixels(finalBitmap)

        val detection =
          try {
            catalog.ocrImageDetect(ocrImage, maxImageSize)
          } catch (e: uniffi.bindings.CatalogException) {
            Log.d("OCR", "detect failed: ${e.message}")
            null
          }
        detection?.let { boxes ->
          onDetectedRegions(boxes.map { it.orientedBox }, finalBitmap.width, finalBitmap.height)
        }

        val plan =
          try {
            catalog.ocrImagePlan(
              ocrImage,
              maxImageSize,
              sourceSelection,
              to,
              minConfidence,
              readingOrder,
              backgroundMode,
              detection,
            )
          } catch (e: uniffi.bindings.CatalogException.MissingAsset) {
            Log.d("OCR", "ocr failed: ${e.message}")
            val missingDetected =
              if (isAutoSource) {
                detectedLanguageCodeFromMissingAsset(e.message)?.let(catalog::languageByCode)
              } else {
                null
              }
            if (missingDetected != null) {
              onMissingDetectedLanguage(missingDetected)
            } else {
              // Not a translation-pair gap: the OCR model chain itself is
              // broken/missing (e.g. detector pack) — actionable via repair.
              onOcrUnavailable()
            }
            ocrImage.close()
            return@withContext null
          } catch (e: uniffi.bindings.CatalogException) {
            Log.d("OCR", "ocr failed: ${e.message}")
            ocrImage.close()
            return@withContext null
          }
        _isOcrInProgress.value = false
        Log.i(
          "TranslationCoordinator",
          "detect+ocr (lockpixels, no marshal): ${System.currentTimeMillis() - translateStart}ms",
        )
        Log.d("OCR", "complete, blocks=${plan.blocks.size}")

        val extractedText = plan.extractedText
        onMessage(TranslatorMessage.ImageTextDetected(extractedText))

        val output =
          Bitmap.createBitmap(finalBitmap.width, finalBitmap.height, Bitmap.Config.ARGB_8888)
        val dstAddr = NativeBitmap.lockPixels(output)
        if (dstAddr == 0L) {
          Log.e("TranslationCoordinator", "lockPixels(output) failed")
          ocrImage.close()
          return@withContext null
        }
        var translatedWords: List<uniffi.translator_core.PositionedWord> = emptyList()
        val renderMs =
          measureTimeMillis {
            translatedWords =
              try {
                catalog.ocrImageRenderInto(ocrImage, plan, to, MIN_OVERLAY_FONT_SIZE_PX, dstAddr)
              } finally {
                NativeBitmap.unlockPixels(output)
              }
          }
        Log.i("TranslationCoordinator", "renderInto took ${renderMs}ms")
        Log.i("TranslationCoordinator", "OCR+translate+.. total: ${System.currentTimeMillis() - totalStart}ms")

        ProcessedImageResult(
          correctedBitmap = output,
          extractedText = extractedText,
          translatedText = plan.translatedText,
          metadata = plan,
          translatedWords = translatedWords,
          ocrImage = ocrImage,
        )
      } catch (e: Exception) {
        Log.e("TranslationCoordinator", "Exception ${e.stackTrace}")
        null
      } finally {
        _isOcrInProgress.value = false
        _isTranslating.value = false
      }
    }

  suspend fun retranslateImageWithOverlay(
    cachedPlan: PreparedImageOverlay,
    from: Language,
    to: Language,
    onMessage: (TranslatorMessage.ImageTextDetected) -> Unit,
    ocrImage: uniffi.bindings.OcrImage? = null,
  ): ProcessedImageResult? =
    withContext(Dispatchers.IO) {
      _isTranslating.value = true
      try {
        val catalog = imageProcessor.loadCatalog() ?: return@withContext null

        // Fast path: the source pixels are still owned rust-side, so re-render the cached OCR
        // result into a fresh display bitmap without re-OCR or any image copy across the FFI.
        if (ocrImage != null) {
          val output =
            Bitmap.createBitmap(
              cachedPlan.width.toInt(),
              cachedPlan.height.toInt(),
              Bitmap.Config.ARGB_8888,
            )
          val dstAddr = NativeBitmap.lockPixels(output)
          if (dstAddr == 0L) {
            Log.e("TranslationCoordinator", "lockPixels(retranslate output) failed")
            return@withContext null
          }
          val res =
            try {
              catalog.ocrImageRetranslateInto(
                ocrImage,
                cachedPlan,
                from,
                to,
                MIN_OVERLAY_FONT_SIZE_PX,
                dstAddr,
              )
            } catch (e: uniffi.bindings.CatalogException) {
              Log.d("OCR", "retranslateInto failed: ${e.message}")
              NativeBitmap.unlockPixels(output)
              return@withContext null
            } finally {
              NativeBitmap.unlockPixels(output)
            }
          val extractedText = res.plan.extractedText
          onMessage(TranslatorMessage.ImageTextDetected(extractedText))
          return@withContext ProcessedImageResult(
            correctedBitmap = output,
            extractedText = extractedText,
            translatedText = res.plan.translatedText,
            metadata = res.plan,
            translatedWords = res.words,
            ocrImage = ocrImage,
          )
        }

        val plan =
          try {
            catalog.retranslateImagePlan(cachedPlan, from, to)
          } catch (e: uniffi.bindings.CatalogException) {
            Log.d("OCR", "retranslateImagePlan failed: ${e.message}")
            return@withContext null
          }

        val extractedText = plan.extractedText
        onMessage(TranslatorMessage.ImageTextDetected(extractedText))
        lateinit var overlayBitmap: Bitmap
        var translatedWords: List<uniffi.translator_core.PositionedWord> = emptyList()
        val translatePaint =
          measureTimeMillis {
            val rendered = catalog.renderTranslatedOverlay(plan, to, MIN_OVERLAY_FONT_SIZE_PX)
            overlayBitmap = bitmapFromRgba(rendered.rgbaBytes, plan.width.toInt(), plan.height.toInt())
              ?: return@withContext null
            translatedWords = rendered.translatedWords
          }
        Log.i("TranslationCoordinator", "Retranslate overpainting took ${translatePaint}ms")

        ProcessedImageResult(
          correctedBitmap = overlayBitmap,
          extractedText = extractedText,
          translatedText = plan.translatedText,
          metadata = plan,
          translatedWords = translatedWords,
        )
      } catch (e: Exception) {
        Log.e("TranslationCoordinator", "Exception ${e.stackTrace}")
        null
      } finally {
        _isTranslating.value = false
      }
    }

  fun transliterate(
    text: String,
    from: Language,
  ): String? = translationService.transliterate(text, from)

  private fun detectedLanguageCodeFromMissingAsset(message: String?): String? {
    if (message == null) return null
    val direction = Regex("\\b([a-z]{2,3}(?:-[A-Za-z0-9]+)?)->[a-z]{2,3}(?:-[A-Za-z0-9]+)?\\b").find(message)
    return direction?.groupValues?.getOrNull(1)
  }

  suspend fun synthesizeSpeech(
    language: Language,
    text: String,
  ): SpeechSynthesisResult = speechService.synthesizeSpeech(language, text)

  suspend fun availableTtsVoices(language: Language): List<TtsVoiceOption> = speechService.availableTtsVoices(language)

  suspend fun installedTtsVoices(language: Language): List<uniffi.translator_core.InstalledTtsPack> =
    speechService.installedTtsVoices(
      language,
    )

  private fun bitmapFromRgba(
    bytes: ByteArray,
    width: Int,
    height: Int,
  ): Bitmap? {
    return try {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
      }
    } catch (e: Exception) {
      Log.e("TranslationCoordinator", "Failed to decode rendered overlay bitmap", e)
      null
    }
  }

  private companion object {
    const val MIN_OVERLAY_FONT_SIZE_PX: Float = 8.0f
  }
}

data class ProcessedImageResult(
  val correctedBitmap: Bitmap,
  val extractedText: String,
  val translatedText: String,
  val metadata: PreparedImageOverlay,
  // Per-word boxes of the rendered translation (image space). Source-word boxes for the
  // original text live on `metadata.sourceWords`. Both drive drag-to-copy.
  val translatedWords: List<uniffi.translator_core.PositionedWord>,
  // Rust-side owner of the source pixels; reused for a language switch without re-OCR/copy.
  val ocrImage: uniffi.bindings.OcrImage? = null,
)

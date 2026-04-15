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

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

class TranslationService(
  private val settingsManager: SettingsManager,
  private val filePathManager: FilePathManager,
) {
  companion object {
    fun cleanup() {
      // cache now lives in the native catalog translation layer
    }
  }

  private var mucabBinding: MucabBinding? = null
  private val transliterateBinding = TransliterateBinding()

  fun setMucabBinding(binding: MucabBinding?) {
    mucabBinding = binding
  }

  // / Requires the translation pairs to be available
  suspend fun preloadModel(
    from: Language,
    to: Language,
  ) = withContext(Dispatchers.IO) {
    if (from == to) return@withContext

    val catalog = filePathManager.loadCatalog() ?: return@withContext
    catalog.translateTexts(from, to, emptyArray()) ?: return@withContext
  }

  suspend fun translateMultiple(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): BatchTranslationResult =
    withContext(Dispatchers.IO) {
      if (from == to) {
        return@withContext BatchTranslationResult.Success(texts.map { TranslatedText(it, null) })
      }
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext BatchTranslationResult.Error("Catalog unavailable")
      val result =
        catalog.translateTexts(from, to, texts)
          ?: return@withContext BatchTranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
      val elapsed =
        measureTimeMillis {
          // translation already executed in native layer
        }
      Log.d("TranslationService", "bulk translation took ${elapsed}ms")
      val translated =
        result.map { translatedText ->
          val transliterated =
            if (settingsManager.settings.value.enableOutputTransliteration) {
              transliterate(translatedText, to)
            } else {
              null
            }
          TranslatedText(translatedText, transliterated)
        }
      return@withContext BatchTranslationResult.Success(translated)
    }

  suspend fun translateMixedTexts(
    inputs: List<String>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
  ): BatchTextTranslationOutput =
    withContext(Dispatchers.IO) {
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext BatchTextTranslationOutput.NothingToTranslate(NothingReason.NO_TRANSLATABLE_TEXT)

      val result =
        catalog.translateMixedTexts(inputs, forcedSourceLanguage, targetLanguage, availableLanguages)

      val nothingReason = result.nothingReason
      if (nothingReason != null && result.translations.isEmpty()) {
        return@withContext BatchTextTranslationOutput.NothingToTranslate(nothingReason)
      }

      val translatedByText = linkedMapOf<String, String>()
      result.translations.forEach { translation ->
        translatedByText[translation.sourceText] = translation.translatedText
      }
      BatchTextTranslationOutput.Translated(translatedByText)
    }

  suspend fun translateStructuredFragments(
    fragments: List<StyledFragment>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
    screenshot: android.graphics.Bitmap?,
  ): StructuredFragmentTranslationOutput =
    withContext(Dispatchers.IO) {
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext StructuredFragmentTranslationOutput.Error("Catalog unavailable")
      val result =
        catalog.translateStructuredFragments(
          fragments = fragments,
          forcedSourceLanguage = forcedSourceLanguage,
          targetLanguage = targetLanguage,
          availableLanguages = availableLanguages,
          screenshot = screenshot,
          backgroundMode = settingsManager.settings.value.backgroundMode,
        )

      result.errorMessage?.let { message ->
        return@withContext StructuredFragmentTranslationOutput.Error(message)
      }
      val nothingReason = result.nothingReason
      if (nothingReason != null && result.blocks.isEmpty()) {
        return@withContext StructuredFragmentTranslationOutput.NothingToTranslate(nothingReason)
      }
      StructuredFragmentTranslationOutput.Success(result.blocks)
    }

  suspend fun translate(
    from: Language,
    to: Language,
    text: String,
  ): TranslationResult =
    withContext(Dispatchers.IO) {
      if (from == to) {
        return@withContext TranslationResult.Success(TranslatedText(text, null))
      }
      // numbers don't translate :^)
      if (text.trim().toFloatOrNull() != null) {
        return@withContext TranslationResult.Success(TranslatedText(text, null))
      }

      if (text.isBlank()) {
        return@withContext TranslationResult.Success(TranslatedText("", null))
      }

      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext TranslationResult.Error("Catalog unavailable")
      val results =
        catalog.translateTexts(from, to, arrayOf(text))
          ?: return@withContext TranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")

      try {
        val elapsed =
          measureTimeMillis {
            // translation already executed in native layer
          }
        Log.d("TranslationService", "Translation took ${elapsed}ms")
        val result = results.first()
        val transliterated =
          if (settingsManager.settings.value.enableOutputTransliteration) {
            transliterate(result, to)
          } else {
            null
          }
        TranslationResult.Success(TranslatedText(result, transliterated))
      } catch (e: Exception) {
        Log.e("TranslationService", "Translation failed", e)
        TranslationResult.Error("Translation failed: ${e.message}")
      }
    }

  suspend fun translateMultipleWithAlignment(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): BatchAlignedTranslationResult =
    withContext(Dispatchers.IO) {
      if (from == to) {
        return@withContext BatchAlignedTranslationResult.Success(
          texts.map { TranslationWithAlignment(it, it, emptyArray()) },
        )
      }
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext BatchAlignedTranslationResult.Error("Catalog unavailable")
      val results =
        catalog.translateTextsWithAlignment(from, to, texts)
          ?: return@withContext BatchAlignedTranslationResult.Error(
            "Language pair ${from.code} -> ${to.code} not installed",
          )

      try {
        val elapsed =
          measureTimeMillis {
            // translation already executed in native layer
          }
        Log.d("TranslationService", "aligned translation took ${elapsed}ms")
        BatchAlignedTranslationResult.Success(results.toList())
      } catch (e: Exception) {
        Log.e("TranslationService", "Aligned translation failed", e)
        BatchAlignedTranslationResult.Error("Translation failed: ${e.message}")
      }
    }

  fun transliterate(
    text: String,
    from: Language,
  ): String? {
    val settings = settingsManager.settings.value
    return try {
      transliterateBinding.transliterate(
        text = text,
        languageCode = from.code,
        sourceScript = from.script,
        japaneseDictPtr = mucabBinding?.dictionaryHandle() ?: 0L,
        japaneseSpaced = settings.addSpacesForJapaneseTransliteration,
      )
    } catch (e: Exception) {
      Log.w("TranslationService", "Failed to transliterate text for $from", e)
      null
    }
  }
}

sealed class TranslationResult {
  data class Success(
    val result: TranslatedText,
  ) : TranslationResult()

  data class Error(
    val message: String,
  ) : TranslationResult()
}

sealed class BatchTranslationResult {
  data class Success(
    val result: List<TranslatedText>,
  ) : BatchTranslationResult()

  data class Error(
    val message: String,
  ) : BatchTranslationResult()
}

sealed class BatchAlignedTranslationResult {
  data class Success(
    val results: List<TranslationWithAlignment>,
  ) : BatchAlignedTranslationResult()

  data class Error(
    val message: String,
  ) : BatchAlignedTranslationResult()
}

sealed class StructuredFragmentTranslationOutput {
  data class Success(
    val blocks: List<uniffi.translator.TranslatedStyledBlock>,
  ) : StructuredFragmentTranslationOutput()

  data class NothingToTranslate(
    val reason: NothingReason,
  ) : StructuredFragmentTranslationOutput()

  data class Error(
    val message: String,
  ) : StructuredFragmentTranslationOutput()
}

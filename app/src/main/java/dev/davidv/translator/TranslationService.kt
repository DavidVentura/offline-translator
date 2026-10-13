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
import uniffi.bindings.CatalogException

class TranslationService(
  private val settingsManager: SettingsManager,
  private val filePathManager: FilePathManager,
) {
  companion object {
    fun cleanup() {
      // cache now lives in the native catalog translation layer
    }
  }

  private val transliterateBinding = TransliterateBinding()

  // / Requires the translation pairs to be available
  suspend fun preloadModel(
    from: Language,
    to: Language,
  ) = withContext(Dispatchers.IO) {
    if (from == to) return@withContext

    val catalog = filePathManager.loadCatalog() ?: return@withContext
    catalog.warmTranslationModels(from, to)
  }

  suspend fun translateHtmlFragments(
    from: Language,
    to: Language,
    fragments: List<String>,
  ): List<String> =
    withContext(Dispatchers.IO) {
      if (fragments.isEmpty() || from == to) return@withContext fragments
      val catalog = filePathManager.loadCatalog() ?: return@withContext fragments
      try {
        catalog.translateHtmlFragments(from, to, fragments)
      } catch (e: CatalogException) {
        Log.w("TranslationService", "translateHtmlFragments failed")
        fragments
      }
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

  suspend fun translate(
    from: Language,
    to: Language,
    text: String,
  ): TranslationResult =
    withContext(Dispatchers.IO) {
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext TranslationResult.Error("Catalog unavailable")
      val withAlternatives =
        try {
          catalog.translateTextWithAlternatives(from, to, text)
        } catch (e: CatalogException.MissingAsset) {
          return@withContext TranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
        } catch (e: CatalogException.Other) {
          Log.e("TranslationService", "Translation failed")
          return@withContext TranslationResult.Error("Translation failed: ${e.message}")
        }
      val result = withAlternatives.translatedText

      val transliterated =
        if (settingsManager.settings.value.enableOutputTransliteration) {
          transliterate(result, to)
        } else {
          null
        }
      TranslationResult.Success(
        TranslatedText(result, transliterated, source = text, alternatives = withAlternatives.alternatives),
      )
    }

  /**
   * Re-translate [source] forcing [forcedPrefix] as the start of the output
   * (the confirmed target text up to and including a user-picked word), then
   * free-run the rest. Returns the steered translation with fresh alternatives.
   */
  suspend fun steer(
    from: Language,
    to: Language,
    source: String,
    forcedPrefix: String,
  ): TranslationResult =
    withContext(Dispatchers.IO) {
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext TranslationResult.Error("Catalog unavailable")
      val steered =
        try {
          catalog.steer(from, to, source, forcedPrefix)
        } catch (e: CatalogException.MissingAsset) {
          return@withContext TranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
        } catch (e: CatalogException.Other) {
          Log.e("TranslationService", "Steer failed")
          return@withContext TranslationResult.Error("Steer failed: ${e.message}")
        }
      val result = steered.translatedText
      val transliterated =
        if (settingsManager.settings.value.enableOutputTransliteration) {
          transliterate(result, to)
        } else {
          null
        }
      TranslationResult.Success(
        TranslatedText(result, transliterated, source = source, alternatives = steered.alternatives),
      )
    }

  /**
   * Abort the in-flight document translation. Non-suspending so the cancel
   * button can call it directly; the cached catalog is the same instance
   * running the translation, so slimt's workers stop within ~one batch.
   */
  fun cancelOngoingWork() {
    filePathManager.loadCatalog()?.cancelOngoingWork()
  }

  suspend fun translateDocumentPath(
    inputPath: String,
    outputPath: String,
    from: Language,
    to: Language,
    translatePdfImages: Boolean,
    txtLayout: TxtLayoutChoice,
    onProgress: (DocumentTranslationProgress) -> Unit = {},
    isCancelled: () -> Boolean = { false },
  ): Result<String> =
    withContext(Dispatchers.IO) {
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext Result.failure(IllegalStateException("Catalog unavailable"))
      try {
        Result.success(
          catalog.translateDocumentPath(inputPath, outputPath, from, to, translatePdfImages, txtLayout, onProgress, isCancelled),
        )
      } catch (e: CatalogException.MissingAsset) {
        Result.failure(IllegalStateException("Language pair ${from.code} -> ${to.code} not installed", e))
      } catch (e: CatalogException.Cancelled) {
        // User-initiated cancel — not an error, no stack trace logged.
        Result.failure(kotlinx.coroutines.CancellationException("translation cancelled"))
      } catch (e: CatalogException.Other) {
        Log.e("TranslationService", "Document translation failed")
        Result.failure(IllegalStateException("Document translation failed: ${e.message}", e))
      }
    }

  fun transliterate(
    text: String,
    from: Language,
  ): String? {
    val settings = settingsManager.settings.value
    val mucabPath = filePathManager.getMucabFile().takeIf { it.exists() }?.absolutePath
    return try {
      transliterateBinding.transliterate(
        text = text,
        languageCode = from.code,
        writingSystem = from.writingSystem,
        japaneseDictPath = mucabPath,
        japaneseSpaced = settings.addSpacesForJapaneseTransliteration,
      )
    } catch (e: Exception) {
      Log.w("TranslationService", "Text transliteration failed")
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

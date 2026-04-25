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
        Log.w("TranslationService", "translateHtmlFragments failed", e)
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
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext TranslationResult.Error("Catalog unavailable")
      val result =
        try {
          catalog.translateText(from, to, text)
        } catch (e: CatalogException.MissingAsset) {
          return@withContext TranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
        } catch (e: CatalogException.Other) {
          Log.e("TranslationService", "Translation failed", e)
          return@withContext TranslationResult.Error("Translation failed: ${e.message}")
        }

      val transliterated =
        if (settingsManager.settings.value.enableOutputTransliteration) {
          transliterate(result, to)
        } else {
          null
        }
      TranslationResult.Success(TranslatedText(result, transliterated))
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
        sourceScript = from.script,
        japaneseDictPath = mucabPath,
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

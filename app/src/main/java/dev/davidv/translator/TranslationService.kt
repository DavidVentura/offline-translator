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
import dev.davidv.bergamot.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

class TranslationService(
  private val settingsManager: SettingsManager,
  val filePathManager: FilePathManager,
) {
  companion object {
    @Volatile
    private var nativeLibInstance: NativeLib? = null

    private fun getNativeLib(): NativeLib =
      nativeLibInstance ?: synchronized(this) {
        nativeLibInstance ?: NativeLib().also {
          Log.d("TranslationService", "Initialized bergamot")
          nativeLibInstance = it
        }
      }

    fun cleanup() {
      synchronized(this) {
        nativeLibInstance?.cleanup()
        nativeLibInstance = null
      }
    }
  }

  private val nativeLib = getNativeLib()

  // / Requires the translation pairs to be available
  suspend fun preloadModel(
    from: Language,
    to: Language,
  ) = withContext(Dispatchers.IO) {
    val translationPairs = getTranslationPairs(from, to)
    for (pair in translationPairs) {
      val config = generateConfig(pair.first, pair.second)
      val languageCode = "${pair.first.code}${pair.second.code}"
      Log.d("TranslationService", "Preloading model with key: $languageCode")
      nativeLib.stringFromJNI(config, ".", languageCode) // translate empty string to load the model
      Log.d("TranslationService", "Preloaded model for ${pair.first} -> ${pair.second} with key: $languageCode")
    }
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

      val translationPairs = getTranslationPairs(from, to)

      // Validate all required language pairs are available
      for (pair in translationPairs) {
        val lang =
          if (pair.first == Language.ENGLISH) {
            pair.second
          } else {
            pair.first
          }
        val dataPath = filePathManager.getDataDir()
        if (missingFilesFrom(dataPath, lang).second.isNotEmpty()) {
          return@withContext TranslationResult.Error("Language pair ${pair.first} -> ${pair.second} not installed")
        }
      }

      try {
        val result: String
        val elapsed =
          measureTimeMillis {
            result = performTranslation(translationPairs, text)
          }
        Log.d("TranslationService", "Translation took ${elapsed}ms")
        val transliterated =
          if (!settingsManager.settings.value.disableTransliteration) {
            TransliterationService.transliterate(result, to)
          } else {
            null
          }
        TranslationResult.Success(TranslatedText(result, transliterated))
      } catch (e: Exception) {
        Log.e("TranslationService", "Translation failed", e)
        TranslationResult.Error("Translation failed: ${e.message}")
      }
    }

  private fun getTranslationPairs(
    from: Language,
    to: Language,
  ): List<Pair<Language, Language>> =
    when {
      from == Language.ENGLISH && to == Language.ENGLISH -> emptyList()
      from == Language.ENGLISH -> listOf(from to to)
      to == Language.ENGLISH -> listOf(from to to)
      else -> listOf(from to Language.ENGLISH, Language.ENGLISH to to) // Pivot through English
    }

  private fun performTranslation(
    pairs: List<Pair<Language, Language>>,
    initialText: String,
  ): String {
    var currentText = initialText
    pairs.forEach { pair ->
      val config = generateConfig(pair.first, pair.second)
      val languageCode = "${pair.first.code}${pair.second.code}"
      currentText = nativeLib.stringFromJNI(config, currentText, languageCode)
    }
    return currentText
  }

  private fun generateConfig(
    fromLang: Language,
    toLang: Language,
  ): String {
    val dataPath = filePathManager.getDataDir()
    val languageFiles =
      if (fromLang == Language.ENGLISH) {
        fromEnglishFiles[toLang]
      } else {
        toEnglishFiles[fromLang]
      } ?: throw IllegalArgumentException("No language files found for $fromLang -> $toLang")

    return """
models:
  - $dataPath/${languageFiles.model.first}
vocabs:
  - $dataPath/${languageFiles.srcVocab.first}
  - $dataPath/${languageFiles.tgtVocab.first}
shortlist:
    - $dataPath/${languageFiles.lex.first}
    - false
beam-size: 1
normalize: 1.0
word-penalty: 0
max-length-break: 128
mini-batch-words: 1024
workspace: 128
max-length-factor: 2.0
skip-cost: true
cpu-threads: 0
quiet: false
quiet-translation: false
gemm-precision: int8shiftAlphaAll
alignment: soft
)"""
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

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
import dev.davidv.bergamot.TranslationWithAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

class TranslationService(
  private val settingsManager: SettingsManager,
  private val filePathManager: FilePathManager,
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

  private var mucabBinding: MucabBinding? = null

  fun setMucabBinding(binding: MucabBinding?) {
    mucabBinding = binding
  }

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
      nativeLib.loadModelIntoCache(config, languageCode) // translate empty string to load the model
      Log.d("TranslationService", "Preloaded model for ${pair.first} -> ${pair.second} with key: $languageCode")
    }
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
      val translationPairs = getTranslationPairs(from, to)

      // TODO: do once
      for (pair in translationPairs) {
        val lang =
          if (pair.first == Language.ENGLISH) {
            pair.second
          } else {
            pair.first
          }
        val dataFiles =
          filePathManager
            .getDataDir()
            .listFiles()
            ?.map { it.name }
            ?.toSet() ?: emptySet()
        // TODO: this should be checked on startup and on update only
        if (missingFilesFrom(dataFiles, lang).second.isNotEmpty()) {
          return@withContext BatchTranslationResult.Error("Language pair ${pair.first} -> ${pair.second} not installed")
        }
      }
      preloadModel(from, to)

      val result: Array<String>
      val elapsed =
        measureTimeMillis {
          result = performTranslations(translationPairs, texts)
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
        val dataFiles =
          filePathManager
            .getDataDir()
            .listFiles()
            ?.map { it.name }
            ?.toSet() ?: emptySet()
        // TODO: this should be checked on startup and on update only
        if (missingFilesFrom(dataFiles, lang).second.isNotEmpty()) {
          return@withContext TranslationResult.Error("Language pair ${pair.first} -> ${pair.second} not installed")
        }
      }

      try {
        val result: String
        val elapsed =
          measureTimeMillis {
            result = performTranslations(translationPairs, arrayOf(text)).first()
          }
        Log.d("TranslationService", "Translation took ${elapsed}ms")
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
      val translationPairs = getTranslationPairs(from, to)

      for (pair in translationPairs) {
        val lang =
          if (pair.first == Language.ENGLISH) {
            pair.second
          } else {
            pair.first
          }
        val dataFiles =
          filePathManager
            .getDataDir()
            .listFiles()
            ?.map { it.name }
            ?.toSet() ?: emptySet()
        if (missingFilesFrom(dataFiles, lang).second.isNotEmpty()) {
          return@withContext BatchAlignedTranslationResult.Error(
            "Language pair ${pair.first} -> ${pair.second} not installed",
          )
        }
      }
      preloadModel(from, to)

      try {
        val results: Array<TranslationWithAlignment>
        val elapsed =
          measureTimeMillis {
            results = performTranslationsWithAlignment(translationPairs, texts)
          }
        Log.d("TranslationService", "aligned translation took ${elapsed}ms")
        BatchAlignedTranslationResult.Success(results.toList())
      } catch (e: Exception) {
        Log.e("TranslationService", "Aligned translation failed", e)
        BatchAlignedTranslationResult.Error("Translation failed: ${e.message}")
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

  // pairs can be len 1 or len 2 only
  private fun performTranslations(
    pairs: List<Pair<Language, Language>>,
    texts: Array<String>,
  ): Array<String> {
    pairs.forEach { pair ->
      val config = generateConfig(pair.first, pair.second)
      val languageCode = "${pair.first.code}${pair.second.code}"
      measureTimeMillis {
        nativeLib.loadModelIntoCache(config, languageCode)
      }
    }
    if (pairs.count() == 1) {
      val code = "${pairs[0].first.code}${pairs[0].second.code}"
      return nativeLib.translateMultiple(texts, code)
    } else if (pairs.count() == 2) {
      val toEng = "${pairs[0].first.code}${pairs[0].second.code}"
      val fromEng = "${pairs[1].first.code}${pairs[1].second.code}"
      return nativeLib.pivotMultiple(toEng, fromEng, texts)
    }
    return emptyArray()
  }

  private fun performTranslationsWithAlignment(
    pairs: List<Pair<Language, Language>>,
    texts: Array<String>,
  ): Array<TranslationWithAlignment> {
    pairs.forEach { pair ->
      val config = generateConfig(pair.first, pair.second)
      val languageCode = "${pair.first.code}${pair.second.code}"
      nativeLib.loadModelIntoCache(config, languageCode)
    }
    if (pairs.count() == 1) {
      val code = "${pairs[0].first.code}${pairs[0].second.code}"
      return nativeLib.translateMultipleWithAlignment(texts, code)
    } else if (pairs.count() == 2) {
      val toEng = "${pairs[0].first.code}${pairs[0].second.code}"
      val fromEng = "${pairs[1].first.code}${pairs[1].second.code}"
      return nativeLib.pivotMultipleWithAlignment(toEng, fromEng, texts)
    }
    return emptyArray()
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
  - $dataPath/${languageFiles.model.name}
vocabs:
  - $dataPath/${languageFiles.srcVocab.name}
  - $dataPath/${languageFiles.tgtVocab.name}
beam-size: 1
normalize: 1.0
word-penalty: 0
max-length-break: 128
mini-batch-words: 1024
max-length-factor: 2.0
skip-cost: true
cpu-threads: 1
quiet: true
quiet-translation: true
gemm-precision: int8shiftAlphaAll
alignment: soft
"""
  }

  fun transliterate(
    text: String,
    from: Language,
  ): String? =
    TransliterationService.transliterate(
      text,
      from,
      mucabBinding = mucabBinding,
      japaneseSpaced = settingsManager.settings.value.addSpacesForJapaneseTransliteration,
    )
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

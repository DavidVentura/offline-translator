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
    @Volatile
    private var translationRuntimeInstance: TranslationRuntime? = null

    private fun getTranslationRuntime(): TranslationRuntime =
      translationRuntimeInstance ?: synchronized(this) {
        translationRuntimeInstance ?: TranslationRuntime().also {
          Log.d("TranslationService", "Initialized translation runtime")
          translationRuntimeInstance = it
        }
      }

    fun cleanup() {
      synchronized(this) {
        translationRuntimeInstance?.cleanup()
        translationRuntimeInstance = null
      }
    }
  }

  private val translationRuntime = getTranslationRuntime()

  private var mucabBinding: MucabBinding? = null

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
    val plan = catalog.resolveTranslationPlan(from, to) ?: return@withContext
    loadPlanIntoCache(plan)
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
      val plan =
        catalog.resolveTranslationPlan(from, to)
          ?: return@withContext BatchTranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
      loadPlanIntoCache(plan)

      val result: Array<String>
      val elapsed =
        measureTimeMillis {
          result = performTranslations(plan, texts)
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

      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext TranslationResult.Error("Catalog unavailable")
      val plan =
        catalog.resolveTranslationPlan(from, to)
          ?: return@withContext TranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
      loadPlanIntoCache(plan)

      try {
        val result: String
        val elapsed =
          measureTimeMillis {
            result = performTranslations(plan, arrayOf(text)).first()
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
      val catalog =
        filePathManager.loadCatalog()
          ?: return@withContext BatchAlignedTranslationResult.Error("Catalog unavailable")
      val plan =
        catalog.resolveTranslationPlan(from, to)
          ?: return@withContext BatchAlignedTranslationResult.Error(
            "Language pair ${from.code} -> ${to.code} not installed",
          )
      loadPlanIntoCache(plan)

      try {
        val results: Array<TranslationWithAlignment>
        val elapsed =
          measureTimeMillis {
            results = performTranslationsWithAlignment(plan, texts)
          }
        Log.d("TranslationService", "aligned translation took ${elapsed}ms")
        BatchAlignedTranslationResult.Success(results.toList())
      } catch (e: Exception) {
        Log.e("TranslationService", "Aligned translation failed", e)
        BatchAlignedTranslationResult.Error("Translation failed: ${e.message}")
      }
    }

  private fun performTranslations(
    plan: TranslationPlan,
    texts: Array<String>,
  ): Array<String> {
    if (plan.steps.size == 1) {
      return translationRuntime.translateMultiple(texts, plan.steps[0].cacheKey)
    } else if (plan.steps.size == 2) {
      return translationRuntime.pivotMultiple(plan.steps[0].cacheKey, plan.steps[1].cacheKey, texts)
    }
    return emptyArray()
  }

  private fun performTranslationsWithAlignment(
    plan: TranslationPlan,
    texts: Array<String>,
  ): Array<TranslationWithAlignment> {
    if (plan.steps.size == 1) {
      return translationRuntime.translateMultipleWithAlignment(texts, plan.steps[0].cacheKey)
    } else if (plan.steps.size == 2) {
      return translationRuntime.pivotMultipleWithAlignment(plan.steps[0].cacheKey, plan.steps[1].cacheKey, texts)
    }
    return emptyArray()
  }

  private fun loadPlanIntoCache(plan: TranslationPlan) {
    plan.steps.forEach { step ->
      Log.d("TranslationService", "Preloading model with key: ${step.cacheKey}")
      translationRuntime.loadModelIntoCache(step.config, step.cacheKey)
      Log.d("TranslationService", "Preloaded model ${step.fromCode} -> ${step.toCode} with key: ${step.cacheKey}")
    }
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

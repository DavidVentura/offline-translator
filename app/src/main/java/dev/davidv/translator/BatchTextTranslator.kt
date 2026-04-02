package dev.davidv.translator

import android.util.Log

sealed class BatchTextTranslationOutput {
  data class Translated(
    val results: LinkedHashMap<String, String>,
  ) : BatchTextTranslationOutput()

  data class NothingToTranslate(
    val reason: NothingReason,
  ) : BatchTextTranslationOutput()
}

enum class NothingReason {
  ALREADY_TARGET_LANGUAGE,
  COULD_NOT_DETECT,
  NO_TRANSLATABLE_TEXT,
}

class BatchTextTranslator(
  private val translationCoordinator: TranslationCoordinator,
) {
  suspend fun translateTexts(
    inputs: List<String>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
  ): BatchTextTranslationOutput {
    val textsBySource = linkedMapOf<Language, MutableList<String>>()
    var detectedSameAsTarget = 0
    var undetectedTexts = 0

    if (forcedSourceLanguage != null) {
      textsBySource.getOrPut(forcedSourceLanguage) { mutableListOf() }.addAll(inputs)
    } else {
      for (text in inputs) {
        val source = translationCoordinator.detectLanguageRobust(text, null, availableLanguages)
        when {
          source == null -> undetectedTexts++
          source == targetLanguage -> detectedSameAsTarget++
          else -> textsBySource.getOrPut(source) { mutableListOf() }.add(text)
        }
      }
    }

    if (textsBySource.isEmpty()) {
      val reason =
        when {
          detectedSameAsTarget > 0 && undetectedTexts == 0 -> NothingReason.ALREADY_TARGET_LANGUAGE
          undetectedTexts > 0 && detectedSameAsTarget == 0 -> NothingReason.COULD_NOT_DETECT
          else -> NothingReason.NO_TRANSLATABLE_TEXT
        }
      return BatchTextTranslationOutput.NothingToTranslate(reason)
    }

    val translatedByText = linkedMapOf<String, String>()
    for ((sourceLanguage, texts) in textsBySource) {
      when (val result = translationCoordinator.translateTexts(sourceLanguage, targetLanguage, texts.toTypedArray())) {
        is BatchTranslationResult.Success -> {
          texts.zip(result.result).forEach { (text, translated) ->
            translatedByText[text] = translated.translated
          }
        }
        is BatchTranslationResult.Error -> {
          Log.e("BatchTextTranslator", "Translation error for ${sourceLanguage.displayName}: ${result.message}")
        }
      }
    }

    return BatchTextTranslationOutput.Translated(translatedByText)
  }
}

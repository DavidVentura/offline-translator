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
  private val languageRoutingRuntime = LanguageRoutingRuntime()

  suspend fun translateTexts(
    inputs: List<String>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
  ): BatchTextTranslationOutput {
    val availableByCode = availableLanguages.associateBy { it.code }
    val routingPlan =
      languageRoutingRuntime.planBatchTextTranslation(
        inputs = inputs.toTypedArray(),
        forcedSourceCode = forcedSourceLanguage?.code,
        targetCode = targetLanguage.code,
        availableLanguageCodes = availableLanguages.map { it.code }.toTypedArray(),
      )

    if (routingPlan.nothingReason != null && routingPlan.batches.isEmpty() && routingPlan.passthroughTexts.isEmpty()) {
      return BatchTextTranslationOutput.NothingToTranslate(NothingReason.valueOf(routingPlan.nothingReason))
    }

    val translatedByText = linkedMapOf<String, String>()
    routingPlan.passthroughTexts.forEach { text -> translatedByText[text] = text }
    for (batch in routingPlan.batches) {
      val sourceLanguage = availableByCode[batch.sourceLanguageCode]
      if (sourceLanguage == null) {
        Log.e("BatchTextTranslator", "Missing source language ${batch.sourceLanguageCode} in available language set")
        continue
      }
      when (val result = translationCoordinator.translateTexts(sourceLanguage, targetLanguage, batch.texts)) {
        is BatchTranslationResult.Success -> {
          batch.texts.zip(result.result).forEach { (text, translated) ->
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
